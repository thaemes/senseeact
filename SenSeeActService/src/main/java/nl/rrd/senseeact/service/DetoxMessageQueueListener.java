package nl.rrd.senseeact.service;

import nl.rrd.senseeact.client.model.detox.DetoxMessageQueue;
import nl.rrd.senseeact.client.model.detox.DetoxMessageQueueTable;
import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseAction;
import nl.rrd.senseeact.dao.DatabaseConnection;
import nl.rrd.senseeact.dao.DatabaseCriteria;
import nl.rrd.senseeact.dao.DatabaseSort;
import nl.rrd.senseeact.dao.listener.DatabaseActionListener;
import nl.rrd.senseeact.service.model.DetoxOnsLookup;
import nl.rrd.utils.AppComponents;
import nl.rrd.utils.exception.DatabaseException;
import nl.rrd.utils.schedule.AbstractScheduledTask;
import nl.rrd.utils.schedule.ScheduleParams;
import nl.rrd.utils.schedule.TaskSchedule;
import nl.rrd.utils.schedule.TaskScheduler;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DetoxMessageQueueListener implements DatabaseActionListener {
	private static final int RETRY_INTERVAL = 15 * 60 * 1000;
	private static final Object RETRY_TASK_LOCK = new Object();
	private static final Set<String> RETRY_TASK_PROJECTS = new HashSet<>();

	private final String project;

	public DetoxMessageQueueListener(String project) {
		this.project = project;
		scheduleRetryTask();
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
			if (sendToOns(action.getRecordId(), getString(data.get("user")),
					getString(data.get("payload"))))
				markSent(action.getRecordId());
		}
	}

	private void scheduleRetryTask() {
		synchronized (RETRY_TASK_LOCK) {
			if (RETRY_TASK_PROJECTS.contains(project))
				return;
			TaskScheduler scheduler = AppComponents.get(TaskScheduler.class);
			String taskId = scheduler.generateTaskId();
			scheduler.scheduleTask(null, new RetryUnsentTask(), taskId);
			RETRY_TASK_PROJECTS.add(project);
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

	private boolean sendToOns(String recordId, String ssaId, String payload) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		Integer onsId = findOnsId(ssaId);
		if (ssaId == null || ssaId.isBlank()) {
			logger.error("Detox queue send failed: recordId={}, missing user",
					recordId);
			return false;
		}
		if (onsId == null) {
			logger.error("Detox queue send failed: recordId={}, no ONS mapping for ssaId={}",
					recordId, ssaId);
			return false;
		}
		if (payload == null)
			payload = "";
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
						recordId, ssaId, onsId);
				return true;
			}
			String body = response.body();
			if (body != null && body.length() > 500)
				body = body.substring(0, 500) + "...";
			logger.error("Detox queue send failed: recordId={}, status={}, body={}",
					recordId, response.statusCode(), body);
		} catch (IOException ex) {
			logger.error("Detox queue send failed: recordId={}",
					recordId, ex);
		} catch (InterruptedException ex) {
			logger.error("Detox queue send interrupted: recordId={}",
					recordId, ex);
			Thread.currentThread().interrupt();
		}
		return false;
	}

	private Integer findOnsId(String ssaId) {
		if (ssaId == null || ssaId.isBlank())
			return null;
		DatabaseLoader dbLoader = DatabaseLoader.getInstance();
		DatabaseConnection conn = null;
		try {
			conn = dbLoader.openConnection();
			Database authDb = dbLoader.initAuthDatabase(conn);
			return DetoxOnsLookup.findOnsId(authDb, ssaId);
		} catch (DatabaseException | IOException ex) {
			Logger logger = AppComponents.getLogger(getClass().getSimpleName());
			logger.error("Failed to resolve ONS ID from lookup table for ssaId={}",
					ssaId, ex);
			return null;
		} finally {
			if (conn != null)
				conn.close();
		}
	}

	private String getString(Object value) {
		if (value == null)
			return null;
		return value.toString();
	}

	private void sendUnsent() {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		DatabaseLoader dbLoader = DatabaseLoader.getInstance();
		DatabaseConnection conn = null;
		try {
			conn = dbLoader.openConnection();
			Database projectDb = dbLoader.initProjectDatabase(conn, project);
			if (projectDb == null)
				return;
			DatabaseCriteria criteria = new DatabaseCriteria.Equal("sentToOns",
					0);
			DatabaseSort[] sort = new DatabaseSort[] {
					new DatabaseSort("utcTime", true)
			};
			List<DetoxMessageQueue> unsent = projectDb.select(
					new DetoxMessageQueueTable(), criteria, 0, sort);
			for (DetoxMessageQueue record : unsent) {
				if (record == null || record.isSentToOns())
					continue;
				if (sendToOns(record.getId(), record.getUser(),
						record.getPayload())) {
					markSent(record.getId());
				}
			}
		} catch (DatabaseException | IOException ex) {
			logger.error("Failed to process unsent detox queue messages: " +
					ex.getMessage(), ex);
		} finally {
			if (conn != null)
				conn.close();
		}
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

	private class RetryUnsentTask extends AbstractScheduledTask {
		public RetryUnsentTask() {
			setSchedule(new TaskSchedule.FixedDelay(RETRY_INTERVAL));
		}

		@Override
		public String getName() {
			return DetoxMessageQueueListener.class.getSimpleName() + "." +
					getClass().getSimpleName() + "." + project;
		}

		@Override
		public void run(Object context, String taskId, ZonedDateTime now,
				ScheduleParams scheduleParams) {
			sendUnsent();
		}
	}
}
