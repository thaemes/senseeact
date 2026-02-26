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
	private String type;

	@DatabaseField(value=DatabaseType.TEXT)
	private String payload;

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
	 * @param type the message type (e.g. heartrate, bloodpressure)
	 * @param payload the JSON payload
	 */
	public DetoxMessageQueue(String user, ZonedDateTime tzTime, String type,
			String payload) {
		super(user, tzTime);
		this.type = type;
		this.payload = payload;
	}

	/**
	 * Returns the message type.
	 *
	 * @return the message type
	 */
	public String getType() {
		return type;
	}

	/**
	 * Sets the message type.
	 *
	 * @param type the message type
	 */
	public void setType(String type) {
		this.type = type;
	}

	/**
	 * Returns the compact JSON payload.
	 *
	 * @return the compact JSON payload
	 */
	public String getPayload() {
		return payload;
	}

	/**
	 * Sets the compact JSON payload.
	 *
	 * @param payload the compact JSON payload
	 */
	public void setPayload(String payload) {
		this.payload = payload;
	}
}
