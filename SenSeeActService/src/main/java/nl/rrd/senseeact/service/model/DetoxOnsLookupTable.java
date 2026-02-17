package nl.rrd.senseeact.service.model;

import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseTableDef;
import nl.rrd.utils.exception.DatabaseException;

public class DetoxOnsLookupTable extends DatabaseTableDef<DetoxOnsLookup> {
	public static final String NAME = "detox_ons_lookup";

	private static final int VERSION = 0;

	public DetoxOnsLookupTable() {
		super(NAME, DetoxOnsLookup.class, VERSION, false);
	}

	@Override
	public int upgradeTable(int version, Database db, String physTable)
			throws DatabaseException {
		return 0;
	}
}
