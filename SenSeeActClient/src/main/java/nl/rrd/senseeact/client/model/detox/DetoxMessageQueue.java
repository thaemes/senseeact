package nl.rrd.senseeact.client.model.detox;

import java.time.ZonedDateTime;

import nl.rrd.senseeact.client.model.sample.UTCSample;
import nl.rrd.senseeact.dao.DatabaseField;
import nl.rrd.senseeact.dao.DatabaseType;

/**
 * This class models a message that is queued for the detox module.
 *
 * @author Dennis Hofs (RRD)
 */
public class DetoxMessageQueue extends UTCSample {
	@DatabaseField(value=DatabaseType.STRING, index=true)
	private String ssaId;

	@DatabaseField(value=DatabaseType.INT, index=true)
	private int onsId;

	@DatabaseField(value=DatabaseType.TEXT)
	private String payload;

	@DatabaseField(value=DatabaseType.BYTE, index=true)
	private boolean sentToOns = false;

	/**
	 * Constructs a new empty message. This is used for DataAccessObjects and
	 * JSON serialization. Users should not call this.
	 */
	public DetoxMessageQueue() {
	}

	/**
	 * Constructs a new message at the specified time.
	 *
	 * @param user the user (user ID)
	 * @param tzTime the time
	 * @param ssaId the SenSeeAct ID
	 * @param onsId the ONS ID
	 * @param payload the JSON payload
	 * @param sentToOns true if already sent to ONS
	 */
	public DetoxMessageQueue(String user, ZonedDateTime tzTime, String ssaId,
			int onsId, String payload, boolean sentToOns) {
		super(user, tzTime);
		this.ssaId = ssaId;
		this.onsId = onsId;
		this.payload = payload;
		this.sentToOns = sentToOns;
	}

	/**
	 * Returns the SenSeeAct ID.
	 *
	 * @return the SenSeeAct ID
	 */
	public String getSsaId() {
		return ssaId;
	}

	/**
	 * Sets the SenSeeAct ID.
	 *
	 * @param ssaId the SenSeeAct ID
	 */
	public void setSsaId(String ssaId) {
		this.ssaId = ssaId;
	}

	/**
	 * Returns the ONS ID.
	 *
	 * @return the ONS ID
	 */
	public int getOnsId() {
		return onsId;
	}

	/**
	 * Sets the ONS ID.
	 *
	 * @param onsId the ONS ID
	 */
	public void setOnsId(int onsId) {
		this.onsId = onsId;
	}

	/**
	 * Returns the JSON payload.
	 *
	 * @return the JSON payload
	 */
	public String getPayload() {
		return payload;
	}

	/**
	 * Sets the JSON payload.
	 *
	 * @param payload the JSON payload
	 */
	public void setPayload(String payload) {
		this.payload = payload;
	}

	/**
	 * Returns whether the message is sent to ONS.
	 *
	 * @return true if sent to ONS, false otherwise
	 */
	public boolean isSentToOns() {
		return sentToOns;
	}

	/**
	 * Sets whether the message is sent to ONS.
	 *
	 * @param sentToOns true if sent to ONS, false otherwise
	 */
	public void setSentToOns(boolean sentToOns) {
		this.sentToOns = sentToOns;
	}
}
