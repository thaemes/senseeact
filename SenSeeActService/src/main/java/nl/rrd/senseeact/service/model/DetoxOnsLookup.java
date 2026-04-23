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
 */
public class DetoxOnsLookup extends BaseDatabaseObject {
	@DatabaseField(value=DatabaseType.STRING, index=true)
	private String ssaId;

	@DatabaseField(value=DatabaseType.INT)
	private int onsId;

	@DatabaseField(value=DatabaseType.STRING)
	private String onsInstance;

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

	public String getOnsInstance() {
		return onsInstance;
	}

	public void setOnsInstance(String onsInstance) {
		this.onsInstance = normalizeOnsInstance(onsInstance);
	}

	public static DetoxOnsLookup findBySsaId(Database authDb, String ssaId)
			throws DatabaseException {
		DatabaseCriteria criteria = new DatabaseCriteria.Equal("ssaId", ssaId);
		return authDb.selectOne(new DetoxOnsLookupTable(), criteria, null);
	}

	public static DetoxOnsLookup findByOnsId(Database authDb, int onsId)
			throws DatabaseException {
		DatabaseCriteria criteria = new DatabaseCriteria.Equal("onsId", onsId);
		return authDb.selectOne(new DetoxOnsLookupTable(), criteria, null);
	}

	public static DetoxOnsLookup findByOnsId(Database authDb, int onsId,
			String onsInstance) throws DatabaseException {
		DatabaseCriteria criteria = new DatabaseCriteria.And(
				new DatabaseCriteria.Equal("onsId", onsId),
				new DatabaseCriteria.Equal("onsInstance",
						normalizeOnsInstance(onsInstance)));
		return authDb.selectOne(new DetoxOnsLookupTable(), criteria, null);
	}

	public static Integer findOnsId(Database authDb, String ssaId)
			throws DatabaseException {
		DetoxOnsLookup lookup = findBySsaId(authDb, ssaId);
		if (lookup == null)
			return null;
		return lookup.getOnsId();
	}

	public static String findSsaId(Database authDb, int onsId)
			throws DatabaseException {
		DetoxOnsLookup lookup = findByOnsId(authDb, onsId);
		if (lookup == null)
			return null;
		return lookup.getSsaId();
	}

	public static String findSsaId(Database authDb, int onsId,
			String onsInstance) throws DatabaseException {
		DetoxOnsLookup lookup = findByOnsId(authDb, onsId, onsInstance);
		if (lookup == null)
			return null;
		return lookup.getSsaId();
	}

	public static DetoxOnsLookup save(Database authDb, String ssaId, int onsId)
			throws DatabaseException {
		return save(authDb, ssaId, onsId, null);
	}

	public static DetoxOnsLookup save(Database authDb, String ssaId, int onsId,
			String onsInstance)
			throws DatabaseException {
		DetoxOnsLookup lookup = findBySsaId(authDb, ssaId);
		if (lookup == null) {
			lookup = new DetoxOnsLookup();
			lookup.setSsaId(ssaId);
			lookup.setOnsId(onsId);
			lookup.setOnsInstance(onsInstance);
			authDb.insert(DetoxOnsLookupTable.NAME, lookup);
		} else {
			lookup.setOnsId(onsId);
			lookup.setOnsInstance(onsInstance);
			authDb.update(DetoxOnsLookupTable.NAME, lookup);
		}
		return lookup;
	}

	private static String normalizeOnsInstance(String onsInstance) {
		if (onsInstance == null)
			return null;
		String result = onsInstance.trim();
		if (result.isEmpty())
			return null;
		return result;
	}
}
