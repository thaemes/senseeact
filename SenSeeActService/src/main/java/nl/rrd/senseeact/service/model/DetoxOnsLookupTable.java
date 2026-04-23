package nl.rrd.senseeact.service.model;

import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseColumnDef;
import nl.rrd.senseeact.dao.DatabaseTableDef;
import nl.rrd.senseeact.dao.DatabaseType;
import nl.rrd.utils.exception.DatabaseException;

public class DetoxOnsLookupTable extends DatabaseTableDef<DetoxOnsLookup> {
	public static final String NAME = "detox_ons_lookup";

	private static final int VERSION = 1;

	public DetoxOnsLookupTable() {
		super(NAME, DetoxOnsLookup.class, VERSION, false);
	}

	@Override
	public int upgradeTable(int version, Database db, String physTable)
			throws DatabaseException {
		if (version == 0)
			return upgradeTableV0(db, physTable);
		return 1;
	}

	private int upgradeTableV0(Database db, String physTable)
			throws DatabaseException {
		db.addColumn(physTable, new DatabaseColumnDef("onsInstance",
				DatabaseType.STRING));
		return 1;
	}
}
