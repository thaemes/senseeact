package nl.rrd.senseeact.client.model.detox;

import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseColumnDef;
import nl.rrd.senseeact.dao.DatabaseTableDef;
import nl.rrd.senseeact.dao.DatabaseType;
import nl.rrd.utils.exception.DatabaseException;

public class DetoxMessageQueueTable extends
		DatabaseTableDef<DetoxMessageQueue> {
	public static final String NAME = "detox_message_queue";

	private static final int VERSION = 4;

	public DetoxMessageQueueTable() {
		super(NAME, DetoxMessageQueue.class, VERSION, false);
	}

	@Override
	public int upgradeTable(int version, Database db, String physTable)
			throws DatabaseException {
		if (version == 0)
			return upgradeTableV0(db, physTable);
		else if (version == 1)
			return upgradeTableV1(db, physTable);
		else if (version == 2)
			return upgradeTableV2(db, physTable);
		else if (version == 3)
			return upgradeTableV3(db, physTable);
		else
			return 4;
	}

	private int upgradeTableV0(Database db, String physTable)
			throws DatabaseException {
		return 1;
	}

	private int upgradeTableV1(Database db, String physTable)
			throws DatabaseException {
		db.dropColumn(physTable, "onsId");
		return 2;
	}

	private int upgradeTableV2(Database db, String physTable)
			throws DatabaseException {
		db.dropColumn(physTable, "ssaId");
		return 3;
	}

	private int upgradeTableV3(Database db, String physTable)
			throws DatabaseException {
		db.addColumn(physTable, new DatabaseColumnDef("type",
				DatabaseType.STRING, true));
		db.dropColumn(physTable, "sentToOns");
		return 4;
	}
}
