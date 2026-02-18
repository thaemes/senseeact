package nl.rrd.senseeact.service;

import nl.rrd.senseeact.client.model.detox.DetoxMessageQueue;
import nl.rrd.senseeact.client.model.detox.DetoxMessageQueueTable;
import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseAction;
import nl.rrd.senseeact.dao.DatabaseConnection;
import nl.rrd.senseeact.dao.DatabaseCriteria;
import nl.rrd.senseeact.dao.listener.DatabaseActionListener;
import nl.rrd.utils.AppComponents;
import nl.rrd.utils.exception.DatabaseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DetoxMessageQueueListener implements DatabaseActionListener {
	private final String project;

	public DetoxMessageQueueListener(String project) {
		this.project = project;
	}

	@Override
	public void onAddDatabaseActions(String database, String table,
			List<DatabaseAction> actions) {
		if (!DetoxMessageQueueTable.NAME.equals(table))
			return;
		for (DatabaseAction action : actions) {
			if (action.getAction() != DatabaseAction.Action.INSERT)
				continue;
			Map<?,?> data = action.getData();
			if (data == null)
				continue;
			if (isSentToOns(data))
				continue;
			if (sendToOns(action, data))
				markSent(action.getRecordId());
		}
	}

	private boolean isSentToOns(Map<?,?> data) {
		Object value = data.get("sentToOns");
		if (value == null)
			return false;
		if (value instanceof Boolean boolValue)
			return boolValue;
		if (value instanceof Number numValue)
			return numValue.intValue() != 0;
		if (value instanceof String strValue) {
			if (strValue.equalsIgnoreCase("true"))
				return true;
			if (strValue.equals("1"))
				return true;
		}
		return false;
	}

	private boolean sendToOns(DatabaseAction action, Map<?,?> data) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		Object payloadObj = data.get("payload");
		String payload = payloadObj == null ? "" : payloadObj.toString();
		try {
			java.net.http.HttpClient client =
					java.net.http.HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://host.docker.internal:4899"))
					.header("Content-Type", "text/plain; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(payload))
					.build();
			HttpResponse<String> response = client.send(request,
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				logger.info("Detox queue sent: recordId={}, ssaId={}, onsId={}",
						action.getRecordId(), data.get("ssaId"),
						data.get("onsId"));
				return true;
			}
			String body = response.body();
			if (body != null && body.length() > 500)
				body = body.substring(0, 500) + "...";
			logger.error("Detox queue send failed: recordId={}, status={}, body={}",
					action.getRecordId(), response.statusCode(), body);
		} catch (IOException ex) {
			logger.error("Detox queue send failed: recordId={}",
					action.getRecordId(), ex);
		} catch (InterruptedException ex) {
			logger.error("Detox queue send interrupted: recordId={}",
					action.getRecordId(), ex);
			Thread.currentThread().interrupt();
		}
		return false;
	}

	private void markSent(String recordId) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		DatabaseLoader dbLoader = DatabaseLoader.getInstance();
		DatabaseConnection conn = null;
		try {
			conn = dbLoader.openConnection();
			Database projectDb = dbLoader.initProjectDatabase(conn, project);
			DatabaseCriteria criteria = new DatabaseCriteria.Equal("id",
					recordId);
			DetoxMessageQueue record = projectDb.selectOne(
					new DetoxMessageQueueTable(), criteria, null);
			if (record == null) {
				logger.error("Detox queue record not found for sent update: id={}",
						recordId);
				return;
			}
			Map<String,Object> values = new LinkedHashMap<>();
			values.put("sentToOns", 1);
			projectDb.update(new DetoxMessageQueueTable(), criteria, values);
			DetoxMessageQueue updated = projectDb.selectOne(
					new DetoxMessageQueueTable(), criteria, null);
			if (updated != null && !updated.isSentToOns()) {
				logger.error("Detox queue sent flag did not update: id={}",
						recordId);
			}
		} catch (DatabaseException | IOException ex) {
			logger.error("Failed to mark detox queue message as sent: " +
					ex.getMessage(), ex);
		} finally {
			if (conn != null)
				conn.close();
		}
	}
}
