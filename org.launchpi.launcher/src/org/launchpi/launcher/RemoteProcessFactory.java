package org.launchpi.launcher;


import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.AbstractJavaLaunchConfigurationDelegate;
import org.eclipse.rse.core.RSECorePlugin;
import org.eclipse.rse.core.model.IHost;
import org.eclipse.rse.core.model.ISystemRegistry;
import org.eclipse.rse.core.subsystems.ISubSystem;
import org.eclipse.rse.services.files.IFileService;
import org.eclipse.rse.services.shells.IHostShell;
import org.eclipse.rse.services.shells.IShellService;
import org.eclipse.rse.shells.ui.RemoteCommandHelpers;
import org.eclipse.rse.subsystems.files.core.servicesubsystem.IFileServiceSubSystem;
import org.eclipse.rse.subsystems.shells.core.subsystems.IRemoteCmdSubSystem;
import org.eclipse.rse.subsystems.shells.core.subsystems.servicesubsystem.IShellServiceSubSystem;

public class RemoteProcessFactory {

	
	public static RemoteProcess createRemoteProcess(ILaunch launch, AbstractJavaLaunchConfigurationDelegate delegate, ILaunchConfiguration configuration, String mode, IProgressMonitor monitor) throws Exception{
		String cfgSystem = configuration.getAttribute(RPIConfigurationAttributes.SYSTEM, "");
		String cfgSystemProfileName = configuration.getAttribute(RPIConfigurationAttributes.SYSTEM_PROFILE, "");
		
		ISystemRegistry registry = RSECorePlugin.getTheSystemRegistry();
		IHost host = registry.getHost(registry.getSystemProfile(cfgSystemProfileName), cfgSystem);
		
		monitor.subTask("Initializing connection to remote host");
		IRemoteCmdSubSystem ss = RemoteCommandHelpers.getCmdSubSystem(host);
		ss.connect(monitor, false);
		monitor.worked(1);
				
		IShellService shellService = getShellService(host);
		IProject project = delegate.getJavaProject(configuration).getProject();
		ProjectSynchronizer synchronizer = new ProjectSynchronizer(project, host);
		synchronizer.synchronize(monitor);

		String cmd = buildCommandLine(delegate, configuration, mode);
		monitor.subTask("Launching remote java process");
		IHostShell shell = shellService.runCommand(getFileService(host).getUserHome().getAbsolutePath() + "/" + ProjectSynchronizer.REMOTE_FOLDER_NAME, cmd, new String[0], new NullProgressMonitor());
		monitor.worked(1);
		return new RemoteProcess(launch, shell, ss);

	}
	
	private static String buildCommandLine(AbstractJavaLaunchConfigurationDelegate delegate, ILaunchConfiguration configuration, String mode) throws CoreException {
		String javaCmd = configuration.getAttribute(RPIConfigurationAttributes.JAVA_CMD,  RPIConfigurationAttributes.DEFAULT_JAVA_CMD);
		StringBuilder cmdBuf = new StringBuilder(javaCmd);
		if (ILaunchManager.DEBUG_MODE.equals(mode)) {
			int debugPort = configuration.getAttribute(RPIConfigurationAttributes.DEBUG_PORT, RPIConfigurationAttributes.DEFAULT_DEBUG_POST);
			cmdBuf.append(" -Xdebug -Xrunjdwp:transport=dt_socket,address=").append(debugPort).append(",server=y,suspend=y");
		}
		
		for (String arg : DebugPlugin.parseArguments(delegate.getVMArguments(configuration))) {
			cmdBuf.append(' ').append(arg.trim());
		}
		cmdBuf.append(" -cp bin:lib/'*'");
		cmdBuf.append(' ').append(delegate.getMainTypeName(configuration));
		
		for (String arg : DebugPlugin.parseArguments(delegate.getProgramArguments(configuration))) {
			cmdBuf.append(' ').append(arg.trim());
		}

		cmdBuf.append(" ; exit");
		return cmdBuf.toString();
		
	}
	
	private static IShellService getShellService(IHost host) {
		for (ISubSystem subSystem : host.getSubSystems()) {
			if (subSystem instanceof IShellServiceSubSystem) {
				return ((IShellServiceSubSystem) subSystem).getShellService();
			}
		}
		throw new IllegalStateException("Cannot find shell service for host " + host.getName());
	}
	
	private static IFileService getFileService(IHost host) throws Exception {
		for (ISubSystem subSystem : host.getSubSystems()) {
			if (subSystem instanceof IFileServiceSubSystem) {
				subSystem.connect(null, true);
				return ((IFileServiceSubSystem) subSystem).getFileService();
			}
		}
		throw new IllegalStateException("File service not found for host " + host.getName());
	}
}
