package nl.rrd.senseeact.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.rrd.senseeact.client.model.detox.DetoxMessageQueueTable;
import nl.rrd.senseeact.client.model.detox.DetoxProcessedMessageQueue;
import nl.rrd.senseeact.client.model.detox.DetoxProcessedMessageQueueTable;
import nl.rrd.senseeact.dao.Database;
import nl.rrd.senseeact.dao.DatabaseAction;
import nl.rrd.senseeact.dao.DatabaseConnection;
import nl.rrd.senseeact.dao.DatabaseCriteria;
import nl.rrd.senseeact.dao.DatabaseSort;
import nl.rrd.senseeact.dao.listener.DatabaseActionListener;
import nl.rrd.senseeact.service.model.DetoxOnsLookup;
import nl.rrd.senseeact.service.model.User;
import nl.rrd.senseeact.service.model.UserCache;
import nl.rrd.senseeact.service.model.UserTable;
import nl.rrd.utils.AppComponents;
import nl.rrd.utils.datetime.DateTimeUtils;
import nl.rrd.utils.exception.DatabaseException;
import nl.rrd.utils.schedule.AbstractScheduledTask;
import nl.rrd.utils.schedule.ScheduleParams;
import nl.rrd.utils.schedule.TaskSchedule;
import nl.rrd.utils.schedule.TaskScheduler;
import org.slf4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DetoxMessageQueueListener implements DatabaseActionListener {
	// Toggle this for endpoint selection.
	private static final boolean USE_ONS_ENDPOINT = true;

	private static final String LOCAL_URL =
			"http://host.docker.internal:4899";
	private static final String ONS_URL =
			"https://api-staging.ons.io/v0/openehr_dossier/back_channel/unauthorized/composition_wrappers";
	private static final String ONS_MTLS_CERT_PATH =
			"/etc/senseeact/ons/client.pem";
	private static final String ONS_MTLS_KEY_PATH =
			"/etc/senseeact/ons/client.key";
	private static final String PROP_OUTGOING_URL =
			"senseeact.detox.outgoing.url";
	private static final String PROP_OUTGOING_MTLS =
			"senseeact.detox.outgoing.mtls";
	private static final int RETRY_INTERVAL = 15 * 60 * 1000;
	private static final Object RETRY_TASK_LOCK = new Object();
	private static final Set<String> RETRY_TASK_PROJECTS = new HashSet<>();
	private static final DateTimeFormatter ONS_UTC_TIME_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

	private final String project;
	private final Object HTTP_CLIENT_LOCK = new Object();
	private final ObjectMapper jsonMapper = new ObjectMapper();
	private java.net.http.HttpClient plainHttpClient =
			java.net.http.HttpClient.newHttpClient();
	private java.net.http.HttpClient mtlsHttpClient = null;
	private String mtlsClientConfigKey = null;

	public DetoxMessageQueueListener(String project) {
		this(project, true);
	}

	DetoxMessageQueueListener(String project, boolean scheduleRetry) {
		this.project = project;
		if (scheduleRetry)
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
			processRawMessage(action.getRecordId(), data);
		}
	}

	private void processRawMessage(String rawRecordId, Map<?,?> data) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		String ssaId = getString(data.get("user"));
		if (ssaId == null || ssaId.isBlank()) {
			logger.error("Detox queue processing failed: recordId={}, missing user",
					rawRecordId);
			return;
		}
		String type = normalizeType(getString(data.get("type")));
		if (type == null) {
			logger.error("Detox queue processing failed: recordId={}, missing/invalid type",
					rawRecordId);
			return;
		}
		Integer onsId = findOnsId(ssaId);
		if (onsId == null) {
			logger.error("Detox queue processing failed: recordId={}, no ONS mapping for ssaId={}",
					rawRecordId, ssaId);
			return;
		}
		String compactPayload = getString(data.get("payload"));
		String processedPayload;
		try {
			ZoneId queueZone = parseZoneId(data.get("timezone"));
			ZoneId effectiveZone = resolveUserZoneId(ssaId, queueZone);
			String fallbackOnsTimestamp = toOnsTimestampString(data.get("utcTime"),
					effectiveZone);
			logger.info(
					"Detox timestamp mapping: recordId={}, queueTimezone={}, effectiveTimezone={}, localTime={}, utcTime={}, onsTimestamp={}",
					rawRecordId, getString(data.get("timezone")),
					effectiveZone.getId(),
					getString(data.get("localTime")), getString(data.get("utcTime")),
					fallbackOnsTimestamp);
			processedPayload = buildProcessedPayload(type, compactPayload, ssaId,
					onsId, fallbackOnsTimestamp, effectiveZone);
		} catch (IllegalArgumentException ex) {
			logger.error("Detox queue processing failed: recordId={}, reason={}",
					rawRecordId, ex.getMessage());
			return;
		}
		DetoxProcessedMessageQueue processed = insertProcessedRecord(ssaId,
				onsId, type, processedPayload);
		if (processed == null)
			return;
		if (sendProcessedToOns(processed.getId(), processed.getUser(),
				processed.getOnsId(), processed.getPayload())) {
			markProcessedSent(processed.getId());
		}
	}

	private String buildProcessedPayload(String type, String compactPayload,
			String ssaId, int onsId, String fallbackOnsTimestamp,
			ZoneId queueZone) {
		Map<String,Object> compact;
		try {
			String parsePayload = compactPayload;
			if (parsePayload == null || parsePayload.isBlank())
				parsePayload = "{}";
			compact = jsonMapper.readValue(parsePayload, new TypeReference<>() {});
		} catch (Exception ex) {
			throw new IllegalArgumentException("Invalid compact payload JSON");
		}
		if ("heartrate".equals(type)) {
			return buildHeartRatePayload(compact, onsId, fallbackOnsTimestamp,
					queueZone);
		} else if ("bloodpressure".equals(type)) {
			return buildBloodPressurePayload(compact, onsId, fallbackOnsTimestamp,
					queueZone);
		} else {
			throw new IllegalArgumentException("Unsupported type: " + type);
		}
	}

	private String buildHeartRatePayload(Map<String,Object> compact, int onsId,
			String fallbackOnsTimestamp, ZoneId queueZone) {
		double bpmValue = requireNumber(compact, "value");
		int bpm = (int)Math.round(bpmValue);
		String comment = "Opmerking";
		if (compact.containsKey("comment")) {
			Object commentValue = compact.get("comment");
			if (commentValue != null && !commentValue.toString().isBlank())
				comment = commentValue.toString();
		}
		String timestamp = resolvePayloadOnsTimestamp(compact, queueZone,
				fallbackOnsTimestamp);
		Map<String,Object> paths = new LinkedHashMap<>();
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/time[1]/value",
				timestamp);
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/data[id2,1]/items[id5,1]/value[id9004,1]/units",
				"/min");
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/data[id2,1]/items[id1060,3]/value[id9032,1]/value",
				comment);
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/data[id2,1]/items[id5,1]/value[id9004,1]/magnitude",
				bpm);
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/data[id2,1]/items[id5,1]/value[id9004,1]/precision",
				0);
		paths.put(
				"/content[id0.0.2,1]/protocol[id11,1]/items[id1020,1]/value[id9022.1,1]/defining_code[1]/code_string",
				"at1035.1");
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/data[id2,1]/items[id6,2]/value[id9005,1]/defining_code[1]/code_string",
				"at7");
		paths.put(
				"/content[id0.0.2,1]/data[id3,1]/events[id4,1]/data[id2,1]/items[id6,2]/value[id9005,1]/defining_code[1]/terminology_id[1]/value",
				"ac9023");
		try {
			String pathsString = jsonMapper.writeValueAsString(paths);
			Map<String,Object> wrapper = new LinkedHashMap<>();
			wrapper.put("clientId", onsId);
			wrapper.put("archetypeId",
					"openEHR-EHR-COMPOSITION.pulse_report.v1.0.0");
			wrapper.put("pathsAndValues", pathsString);
			return jsonMapper.writeValueAsString(wrapper);
		} catch (Exception ex) {
			throw new IllegalArgumentException(
					"Failed to build processed payload JSON");
		}
	}

	private String buildBloodPressurePayload(Map<String,Object> compact,
			int onsId, String fallbackOnsTimestamp, ZoneId queueZone) {
		int diastolic = (int)Math.round(requireNumber(compact, "diastolic"));
		int systolic = (int)Math.round(requireNumber(compact, "systolic"));
		int meanArterialPressure = (int)Math.round(
				requireNumber(compact, "meanArterialPressure", "map"));
		String timestamp = resolvePayloadOnsTimestamp(compact, queueZone,
				fallbackOnsTimestamp);
		String comment = compact.containsKey("comment") ?
				compact.get("comment").toString() : "Opmerking";

		Map<String,Object> paths = new LinkedHashMap<>();
		paths.put("/category[id8,1]/defining_code[1]/code_string", "433");
		paths.put("/category[id8,1]/defining_code[1]/terminology_id[1]/value",
				"openehr");
		paths.put("/category[id8,1]/value", "event");
		paths.put("/composer[1]/external_ref[1]/id[1]/@type", "HIER_OBJECT_ID");
		paths.put("/composer[1]/external_ref[1]/id[1]/value",
				"com.nedap.TE4776.employee::1497");
		paths.put("/composer[1]/external_ref[1]/namespace", "demographic");
		paths.put("/composer[1]/external_ref[1]/type", "PERSON");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id5,1]/value[id9010,1]/magnitude",
				diastolic);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id5,1]/value[id9010,1]/precision",
				0);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id5,1]/value[id9010,1]/units",
				"mm[Hg]");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id6,2]/value[id9011,1]/magnitude",
				systolic);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id6,2]/value[id9011,1]/precision",
				0);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id6,2]/value[id9011,1]/units",
				"mm[Hg]");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id1007,3]/value[id9012,1]/magnitude",
				meanArterialPressure);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id1007,3]/value[id9012,1]/precision",
				0);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id1007,3]/value[id9012,1]/units",
				"mm[Hg]");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/data[id4,1]/items[id34,4]/value[id9014,1]/value",
				comment);
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/state[id8,1]/items[id9,1]/value[id9015,1]/defining_code[1]/code_string",
				"at1002");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/state[id8,1]/items[id9,1]/value[id9015,1]/defining_code[1]/terminology_id[1]/value",
				"ac9061");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/state[id8,1]/items[id9,1]/value[id9015,1]/value",
				"Zittend");
		paths.put("/content[id0.0.2,1]/data[id2,1]/events[id7,1]/time[1]/value",
				timestamp);
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id14,1]/value[id9034,1]/defining_code[1]/code_string",
				"at18");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id14,1]/value[id9034,1]/defining_code[1]/terminology_id[1]/value",
				"ac9039");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id14,1]/value[id9034,1]/value",
				"Volwassene");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id15,2]/value[id9036,1]/@type",
				"DV_CODED_TEXT");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id15,2]/value[id9036,1]/defining_code[1]/code_string",
				"at27");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id15,2]/value[id9036,1]/defining_code[1]/terminology_id[1]/value",
				"ac9066");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id15,2]/value[id9036,1]/value",
				"Linkerarm");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id1036,3]/value[id9052,1]/defining_code[1]/code_string",
				"at1040");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id1036,3]/value[id9052,1]/defining_code[1]/terminology_id[1]/value",
				"ac9054");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id1036,3]/value[id9052,1]/value",
				"Machinaal");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id1011,4]/value[id9037,1]/defining_code[1]/code_string",
				"at1013");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id1011,4]/value[id9037,1]/defining_code[1]/terminology_id[1]/value",
				"ac9009");
		paths.put("/content[id0.0.2,1]/protocol[id12,1]/items[id1011,4]/value[id9037,1]/value",
				"Fase V");
		try {
			String pathsString = jsonMapper.writeValueAsString(paths);
			Map<String,Object> wrapper = new LinkedHashMap<>();
			wrapper.put("clientId", onsId);
			wrapper.put("archetypeId",
					"openEHR-EHR-COMPOSITION.blood_pressure_report.v1.0.0");
			wrapper.put("pathsAndValues", pathsString);
			return jsonMapper.writeValueAsString(wrapper);
		} catch (Exception ex) {
			throw new IllegalArgumentException(
					"Failed to build processed payload JSON");
		}
	}

	private double requireNumber(Map<String,Object> map, String key) {
		Object value = map.get(key);
		if (value == null)
			throw new IllegalArgumentException("Missing required field: " + key);
		if (value instanceof Number number)
			return number.doubleValue();
		if (value instanceof String strValue) {
			try {
				return Double.parseDouble(strValue);
			} catch (NumberFormatException ex) {
				throw new IllegalArgumentException("Invalid number for field: " +
						key);
			}
		}
		throw new IllegalArgumentException("Invalid number for field: " + key);
	}

	private double requireNumber(Map<String,Object> map, String key,
			String fallbackKey) {
		if (map.containsKey(key))
			return requireNumber(map, key);
		return requireNumber(map, fallbackKey);
	}

	private String resolvePayloadOnsTimestamp(Map<String,Object> compact,
			ZoneId fallbackZone, String fallbackOnsTimestamp) {
		ZoneId zone = parseZoneIdIfPresent(compact.get("timeZone"));
		if (zone == null)
			zone = fallbackZone != null ? fallbackZone : ZoneOffset.UTC;
		Object utcMillis = compact.get("timestampUtcMillis");
		if (utcMillis != null)
			return toOnsTimestampString(utcMillis, zone);
		return fallbackOnsTimestamp;
	}

	private String toOnsTimestampString(Object value, ZoneId defaultZone) {
		ZoneId zone = defaultZone != null ? defaultZone : ZoneOffset.UTC;
		if (value == null)
			return formatInstantForOns(Instant.now(), zone);
		if (value instanceof Number number) {
			return formatInstantForOns(Instant.ofEpochMilli(number.longValue()),
					zone);
		}
		String str = value.toString();
		if (str == null || str.isBlank())
			return formatInstantForOns(Instant.now(), zone);
		str = str.trim();
		if (str.matches("^-?\\d+$")) {
			long epoch = Long.parseLong(str);
			if (str.length() <= 10)
				epoch *= 1000L;
			return formatInstantForOns(Instant.ofEpochMilli(epoch), zone);
		}
		throw new IllegalArgumentException("Invalid timestamp format");
	}

	private String formatInstantForOns(Instant instant, ZoneId zone) {
		return ONS_UTC_TIME_FORMAT.format(instant.atZone(zone).toOffsetDateTime());
	}

	private ZoneId parseZoneId(Object zoneValue) {
		if (zoneValue == null)
			return ZoneOffset.UTC;
		String zone = zoneValue.toString();
		if (zone == null || zone.isBlank())
			return ZoneOffset.UTC;
		try {
			return ZoneId.of(zone.trim());
		} catch (Exception ex) {
			return ZoneOffset.UTC;
		}
	}

	private ZoneId parseZoneIdIfPresent(Object zoneValue) {
		if (zoneValue == null)
			return null;
		String zone = zoneValue.toString();
		if (zone == null || zone.isBlank())
			return null;
		try {
			return ZoneId.of(zone.trim());
		} catch (Exception ex) {
			return null;
		}
	}

	private ZoneId resolveUserZoneId(String ssaId, ZoneId fallbackZone) {
		ZoneId defaultZone = fallbackZone != null ? fallbackZone : ZoneOffset.UTC;
		try {
			UserCache userCache = UserCache.getInstance();
			if (userCache != null) {
				User user = userCache.findByUserid(ssaId);
				ZoneId zone = parseZoneIdIfPresent(
						user != null ? user.getTimeZone() : null);
				if (zone != null)
					return zone;
			}
		} catch (Exception ignored) {
			// Fall back to direct DB lookup.
		}
		DatabaseLoader dbLoader = DatabaseLoader.getInstance();
		DatabaseConnection conn = null;
		try {
			conn = dbLoader.openConnection();
			Database authDb = dbLoader.initAuthDatabase(conn);
			DatabaseCriteria criteria = new DatabaseCriteria.Equal("userid", ssaId);
			User user = authDb.selectOne(new UserTable(), criteria, null);
			ZoneId zone = parseZoneIdIfPresent(
					user != null ? user.getTimeZone() : null);
			if (zone != null)
				return zone;
		} catch (Exception ignored) {
			// Use fallback zone.
		} finally {
			if (conn != null)
				conn.close();
		}
		return defaultZone;
	}

	private String normalizeType(String type) {
		if (type == null)
			return null;
		String normalized = type.toLowerCase().replaceAll("[_\\s-]", "");
		if ("heartrate".equals(normalized) || "heart".equals(normalized))
			return "heartrate";
		if ("bloodpressure".equals(normalized) || "blood".equals(normalized))
			return "bloodpressure";
		return null;
	}

	private DetoxProcessedMessageQueue insertProcessedRecord(String user,
			int onsId, String type, String processedPayload) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		DatabaseLoader dbLoader = DatabaseLoader.getInstance();
		DatabaseConnection conn = null;
		try {
			conn = dbLoader.openConnection();
			Database projectDb = dbLoader.initProjectDatabase(conn, project);
			if (projectDb == null)
				return null;
			DetoxProcessedMessageQueue record = new DetoxProcessedMessageQueue(
					user, DateTimeUtils.nowMs(), onsId, type, processedPayload,
					false);
			projectDb.insert(DetoxProcessedMessageQueueTable.NAME, record);
			return record;
		} catch (DatabaseException | IOException ex) {
			logger.error("Failed to insert processed detox queue message: {}",
					ex.getMessage(), ex);
			return null;
		} finally {
			if (conn != null)
				conn.close();
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

	private boolean sendProcessedToOns(String recordId, String ssaId, int onsId,
			String payload) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		OutgoingEndpoint endpoint = getOutgoingEndpoint();
		if (payload == null)
			payload = "";
		try {
			java.net.http.HttpClient client = getHttpClient(endpoint);
			HttpRequest request = HttpRequest.newBuilder()
					.uri(endpoint.url)
					.header("Content-Type", "application/json; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(payload))
					.build();
			HttpResponse<String> response = client.send(request,
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				logger.info(
						"Detox queue sent: recordId={}, endpoint={}, ssaId={}, onsId={}",
						recordId, endpoint.mode, ssaId, onsId);
				return true;
			}
			String body = response.body();
			if (body != null && body.length() > 500)
				body = body.substring(0, 500) + "...";
			logger.error(
					"Detox queue send failed: recordId={}, endpoint={}, status={}, body={}",
					recordId, endpoint.mode, response.statusCode(), body);
		} catch (IOException | GeneralSecurityException ex) {
			logger.error("Detox queue send failed: recordId={}", recordId, ex);
		} catch (InterruptedException ex) {
			logger.error("Detox queue send interrupted: recordId={}", recordId,
					ex);
			Thread.currentThread().interrupt();
		}
		return false;
	}

	private OutgoingEndpoint getOutgoingEndpoint() {
		String overrideUrl = System.getProperty(PROP_OUTGOING_URL);
		if (overrideUrl != null && !overrideUrl.isBlank()) {
			boolean mtls = Boolean.parseBoolean(
					System.getProperty(PROP_OUTGOING_MTLS, "false"));
			return new OutgoingEndpoint("override", URI.create(overrideUrl), mtls);
		}
		if (USE_ONS_ENDPOINT) {
			return new OutgoingEndpoint("ons", URI.create(ONS_URL), true);
		}
		return new OutgoingEndpoint("local", URI.create(LOCAL_URL), false);
	}

	private java.net.http.HttpClient getHttpClient(OutgoingEndpoint endpoint)
			throws GeneralSecurityException, IOException {
		if (!endpoint.requireMtls)
			return plainHttpClient;
		String certPath = ONS_MTLS_CERT_PATH;
		String keyPath = ONS_MTLS_KEY_PATH;
		if (certPath == null || certPath.isBlank() || keyPath == null ||
				keyPath.isBlank()) {
			throw new GeneralSecurityException(
					"mTLS is enabled but cert/key path constants are not configured");
		}
		String configKey = certPath + "|" + keyPath;
		synchronized (HTTP_CLIENT_LOCK) {
			if (mtlsHttpClient != null && configKey.equals(mtlsClientConfigKey))
				return mtlsHttpClient;
			SSLContext sslContext = buildMtlsContext(certPath, keyPath);
			mtlsHttpClient = java.net.http.HttpClient.newBuilder()
					.sslContext(sslContext)
					.build();
			mtlsClientConfigKey = configKey;
			return mtlsHttpClient;
		}
	}

	private SSLContext buildMtlsContext(String certPath, String keyPath)
			throws GeneralSecurityException, IOException {
		X509Certificate cert = readX509Certificate(certPath);
		PrivateKey privateKey = readPrivateKey(keyPath);
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		char[] keyPassword = new char[0];
		keyStore.load(null, null);
		keyStore.setKeyEntry("client", privateKey, keyPassword,
				new java.security.cert.Certificate[] { cert });
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(
				KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(keyStore, keyPassword);
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(
				TrustManagerFactory.getDefaultAlgorithm());
		tmf.init((KeyStore) null);
		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(),
				new SecureRandom());
		return sslContext;
	}

	private X509Certificate readX509Certificate(String certPath)
			throws IOException, GeneralSecurityException {
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		try (InputStream in = Files.newInputStream(Path.of(certPath))) {
			return (X509Certificate) cf.generateCertificate(in);
		}
	}

	private PrivateKey readPrivateKey(String keyPath)
			throws IOException, GeneralSecurityException {
		String pem = Files.readString(Path.of(keyPath), StandardCharsets.UTF_8);
		String normalized = normalizePrivateKeyPem(pem);
		byte[] der = Base64.getDecoder().decode(normalized);
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
		String[] algorithms = new String[] { "RSA", "EC", "DSA" };
		for (String algorithm : algorithms) {
			try {
				return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
			} catch (GeneralSecurityException ex) {
				// Try next algorithm.
			}
		}
		throw new GeneralSecurityException(
				"Unsupported private key format. Expected PKCS#8 PEM (BEGIN PRIVATE KEY).");
	}

	private String normalizePrivateKeyPem(String pem) {
		return pem.replace("-----BEGIN PRIVATE KEY-----", "")
				.replace("-----END PRIVATE KEY-----", "")
				.replaceAll("\\s", "");
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
			logger.error(
					"Failed to resolve ONS ID from lookup table for ssaId={}",
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
			List<DetoxProcessedMessageQueue> unsent = projectDb.select(
					new DetoxProcessedMessageQueueTable(), criteria, 0, sort);
			for (DetoxProcessedMessageQueue record : unsent) {
				if (record == null || record.isSentToOns())
					continue;
				if (sendProcessedToOns(record.getId(), record.getUser(),
						record.getOnsId(), record.getPayload())) {
					markProcessedSent(record.getId());
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

	private void markProcessedSent(String recordId) {
		Logger logger = AppComponents.getLogger(getClass().getSimpleName());
		DatabaseLoader dbLoader = DatabaseLoader.getInstance();
		DatabaseConnection conn = null;
		try {
			conn = dbLoader.openConnection();
			Database projectDb = dbLoader.initProjectDatabase(conn, project);
			DatabaseCriteria criteria = new DatabaseCriteria.Equal("id",
					recordId);
			DetoxProcessedMessageQueue record = projectDb.selectOne(
					new DetoxProcessedMessageQueueTable(), criteria, null);
			if (record == null) {
				logger.error(
						"Detox processed queue record not found for sent update: id={}",
						recordId);
				return;
			}
			Map<String,Object> values = new LinkedHashMap<>();
			values.put("sentToOns", 1);
			projectDb.update(new DetoxProcessedMessageQueueTable(), criteria,
					values);
			DetoxProcessedMessageQueue updated = projectDb.selectOne(
					new DetoxProcessedMessageQueueTable(), criteria, null);
			if (updated != null && !updated.isSentToOns()) {
				logger.error("Detox processed queue sent flag did not update: id={}",
						recordId);
			}
		} catch (DatabaseException | IOException ex) {
			logger.error("Failed to mark detox processed queue message as sent: " +
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

	private static class OutgoingEndpoint {
		private final String mode;
		private final URI url;
		private final boolean requireMtls;

		public OutgoingEndpoint(String mode, URI url, boolean requireMtls) {
			this.mode = mode;
			this.url = url;
			this.requireMtls = requireMtls;
		}
	}
}
