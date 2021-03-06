package net.dorokhov.pony.core.test;

import net.dorokhov.pony.core.installation.InstallCommand;
import net.dorokhov.pony.core.installation.InstallationService;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;

public class AbstractIntegrationCase {

	protected final Logger log = LoggerFactory.getLogger(getClass());

	protected ConfigurableApplicationContext context;

	protected InstallationService installationService;

	@Before
	public void baseSetUp() throws Exception {

		context = new ClassPathXmlApplicationContext("context.xml");

		installationService = context.getBean(InstallationService.class);

		if (installationService.getInstallation() != null) {
			installationService.uninstall();
		}

		installationService.install(new InstallCommand());
	}

	@After
	public void baseTearDown() throws Exception {

		SecurityContextHolder.clearContext();

		if (installationService != null && installationService.getInstallation() != null) {
			installationService.uninstall();
		}

		if (context != null) {
			context.close();
		}
	}

}
