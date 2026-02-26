package nl.rrd.senseeact.exampleclient.project.detox;

import nl.rrd.senseeact.client.model.detox.DetoxMessageQueueTable;
import nl.rrd.senseeact.client.model.detox.DetoxProcessedMessageQueueTable;
import nl.rrd.senseeact.client.project.BaseProject;
import nl.rrd.senseeact.client.sensor.BaseSensor;
import nl.rrd.senseeact.dao.DatabaseTableDef;

import java.util.ArrayList;
import java.util.List;

public class DetoxProject extends BaseProject {
	public DetoxProject() {
		super("detox", "Detox");
	}

	@Override
	public List<BaseSensor> getSensors() {
		return null;
	}

	@Override
	public List<DatabaseTableDef<?>> getDatabaseTables() {
		List<DatabaseTableDef<?>> result = new ArrayList<>();
		result.add(new DetoxMessageQueueTable());
		result.add(new DetoxProcessedMessageQueueTable());
		return result;
	}
}
