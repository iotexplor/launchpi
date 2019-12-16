package org.launchpi.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.rse.subsystems.files.core.servicesubsystem.IFileServiceSubSystem;
import org.eclipse.rse.subsystems.files.core.subsystems.IRemoteFile;
import org.launchpi.launcher.i18n.Messages;
/**
 * 为了加快同步进度，只传修改过的文件
 * @author issac
 *
 */
public class ProjectSynchronizer {
	public static final String REMOTE_FOLDER_NAME = ".launchpi_projects"; //$NON-NLS-1$
	
	private IProject project;

	private IFileServiceSubSystem fileServiceSubsystem;

	private String baseFolderName;

	public ProjectSynchronizer(IProject project, String baseFolderName, IFileServiceSubSystem fileServiceSubsystem) {
		this.project = project;
		this.baseFolderName = baseFolderName;
		this.fileServiceSubsystem = fileServiceSubsystem;
	}
	/**
	  *   同步文件
	 * @param monitor
	 * @throws Exception
	 */
	public void synchronize(IProgressMonitor monitor) throws Exception{
		monitor.subTask(Messages.Progress_Synchronizing_CP);
		IJavaProject javaProject = JavaCore.create(project);
		
		IRemoteFile baseFolder = fileServiceSubsystem.getRemoteFileObject(baseFolderName, monitor);
		if (!baseFolder.exists()) {//如果不存在，创建文件夹
			//fileServiceSubsystem.delete(baseFolder, monitor);
			fileServiceSubsystem.createFolders(baseFolder, monitor);
		}
		ClasspathResolver resolver = new ClasspathResolver(javaProject);
		Collection<File> classpathEntries = resolver.resolve();
		File classpathArchive = createClasspathArchive(classpathEntries);
		ConsoleFactory.println("Upload file size:"+classpathArchive.length());
		IRemoteFile remoteEntry = fileServiceSubsystem.getRemoteFileObject(baseFolder, classpathArchive.getName(), monitor);
		fileServiceSubsystem.upload(classpathArchive.getCanonicalPath(), remoteEntry, null, monitor);
		classpathArchive.delete();
	}
	
	/**
	 * 创建压缩文件
	 * @param classpathEntries
	 * @return
	 * @throws IOException
	 */
	private File createClasspathArchive(Collection<File> classpathEntries) throws IOException {
		File archiveFile = new File(System.getProperty("java.io.tmpdir"), project.getName() + ".tar"); //$NON-NLS-1$ //$NON-NLS-2$
		if (archiveFile.exists()) {
			archiveFile.delete();
		}
		FileOutputStream fos = null;
		TarArchiveOutputStream os = null;
		try {
			fos = new FileOutputStream(archiveFile);
			os = (TarArchiveOutputStream) new ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.TAR, fos);
			os.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
			LinkedList<String> pathElements = new LinkedList<String>();
			for (File f : classpathEntries) {
				if (f.isFile()) {
					pathElements.addLast("lib"); //$NON-NLS-1$
					writeClasspathEntry(pathElements, f, os);
				} else {
					pathElements.addLast("classes"); //$NON-NLS-1$
					for (File child : f.listFiles()) {
						writeClasspathEntry(pathElements, child, os);
					}
				}
				pathElements.removeLast();
			}
			return archiveFile;
		} catch (ArchiveException e) {
			throw new IOException("Failed to create classpath archive", e); //$NON-NLS-1$
		}finally {
			if (os != null) {
				os.close();
			}
			if (fos != null) {
				fos.close();
			}
		}
	}
	
	//存放传输文件的md5值
	private static Map<String,String> fileMd5Maps = new HashMap<String,String>();
	/**
	 * 获取文件MD5值
	 * @param file
	 * @return
	 */
	private static String md5Hex(File file) {
        MessageDigest digest = null;
        FileInputStream fis = null;
        byte[] buffer = new byte[1024];
        try {
            if (!file.isFile()) {
                return "";
            }
            digest = MessageDigest.getInstance("MD5");
            fis = new FileInputStream(file);
 
            while (true) {
                int len;
                if ((len = fis.read(buffer, 0, 1024)) == -1) {
                    fis.close();
                    break;
                }
 
                digest.update(buffer, 0, len);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        BigInteger var5 = new BigInteger(1, digest.digest());
        return String.format("%1$032x", new Object[]{var5});
    }
	
	/**
	  * 写入压缩文件 便于传输
	 * @param pathElements
	 * @param entry
	 * @param os
	 * @throws IOException
	 */
	private void writeClasspathEntry(LinkedList<String> pathElements, File entry, ArchiveOutputStream os) throws IOException {
		if (entry.isFile()) {
			String filePath = entry.getCanonicalPath();
			String existMd5Hex = fileMd5Maps.get(filePath);
			String currentMd5Hex = md5Hex(entry);
			//没有加入过，或者MD5值已经改过的，才加入
			if(existMd5Hex==null || (currentMd5Hex!=null && !existMd5Hex.equals(currentMd5Hex))) {
				fileMd5Maps.put(filePath, md5Hex(entry));
				os.putArchiveEntry(new TarArchiveEntry(entry, getPath(pathElements) + "/" + entry.getName())); //$NON-NLS-1$
				copy(entry, os);
				os.closeArchiveEntry();
			}
		} else {
			pathElements.addLast(entry.getName());
			for (File child : entry.listFiles()) {
				writeClasspathEntry(pathElements, child, os);
			}
			pathElements.removeLast();
		}
	}
	
	private void copy(File entry, OutputStream out) throws IOException {
		FileInputStream in = null;
		try {
			in = new FileInputStream(entry);
			IOUtils.copy(in, out);
		}finally {
			if (in != null) {
				in.close();
			}
		}
	}
	
	private static String getPath(LinkedList<String> pathElements) {
		StringBuilder buf = new StringBuilder();
		for (int i = 0; i < pathElements.size(); i++) {
			if (i != 0) {
				buf.append('/');
			}
			buf.append(pathElements.get(i));
		}
		return buf.toString();
	}
	
}
