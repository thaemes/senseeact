package nl.rrd.senseeact.client.model.detox;

import nl.rrd.senseeact.client.model.sample.UTCSample;
import nl.rrd.senseeact.dao.DatabaseField;
import nl.rrd.senseeact.dao.DatabaseType;

import java.time.ZonedDateTime;

/**
 * Queue record after payload transformation for ONS.
 */
public class DetoxProcessedMessageQueue extends UTCSample {
	@DatabaseField(value=DatabaseType.INT, index=true)
	private int onsId;

	@DatabaseField(value=DatabaseType.STRING, index=true)
	private String type;

	@DatabaseField(value=DatabaseType.TEXT)
	private String payload;

	@DatabaseField(value=DatabaseType.BYTE, index=true)
	private boolean sentToOns = false;

	public DetoxProcessedMessageQueue() {
	}

	public DetoxProcessedMessageQueue(String user, ZonedDateTime tzTime,
			int onsId, String type, String payload, boolean sentToOns) {
		super(user, tzTime);
		this.onsId = onsId;
		this.type = type;
		this.payload = payload;
		this.sentToOns = sentToOns;
	}

	public int getOnsId() {
		return onsId;
	}

	public void setOnsId(int onsId) {
		this.onsId = onsId;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getPayload() {
		return payload;
	}

	public void setPayload(String payload) {
		this.payload = payload;
	}

	public boolean isSentToOns() {
		return sentToOns;
	}

	public void setSentToOns(boolean sentToOns) {
		this.sentToOns = sentToOns;
	}
}
