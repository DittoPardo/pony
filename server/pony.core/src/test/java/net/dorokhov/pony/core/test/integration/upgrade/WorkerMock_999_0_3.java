package net.dorokhov.pony.core.test.integration.upgrade;

import net.dorokhov.pony.core.upgrade.UpgradeWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerMock_999_0_3 implements UpgradeWorker {

	@Override
	public String getVersion() {
		return "999.0.3";
	}

	@Override
	@Transactional
	public void run() {}

}
