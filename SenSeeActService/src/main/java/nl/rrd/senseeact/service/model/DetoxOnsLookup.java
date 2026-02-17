package nl.rrd.senseeact.service.model;

import nl.rrd.senseeact.dao.BaseDatabaseObject;
import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseCriteria;
import nl.rrd.senseeact.dao.DatabaseField;
import nl.rrd.senseeact.dao.DatabaseType;
import nl.rrd.utils.exception.DatabaseException;

/**
 * Lookup table entry to map an SSA ID to an ONS ID.
 *
 * @author Dennis Hofs (RRD)
 */
public class DetoxOnsLookup extends BaseDatabaseObject {
	@DatabaseField(value=DatabaseType.STRING, index=true)
	private String ssaId;

	@DatabaseField(value=DatabaseType.INT)
	private int onsId;

	public String getSsaId() {
		return ssaId;
	}

	public void setSsaId(String ssaId) {
		this.ssaId = ssaId;
	}

	public int getOnsId() {
		return onsId;
	}

	public void setOnsId(int onsId) {
		this.onsId = onsId;
	}

	public static DetoxOnsLookup findBySsaId(Database authDb, String ssaId)
			throws DatabaseException {
		DatabaseCriteria criteria = new DatabaseCriteria.Equal("ssaId", ssaId);
		return authDb.selectOne(new DetoxOnsLookupTable(), criteria, null);
	}

	public static Integer findOnsId(Database authDb, String ssaId)
			throws DatabaseException {
		DetoxOnsLookup lookup = findBySsaId(authDb, ssaId);
		if (lookup == null)
			return null;
		return lookup.getOnsId();
	}
}
