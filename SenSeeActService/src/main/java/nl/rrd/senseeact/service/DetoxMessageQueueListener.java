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
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

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
	private static final int RETRY_INTERVAL = 15 * 60 * 1000;
	private static final Object RETRY_TASK_LOCK = new Object();
	private static final Set<String> RETRY_TASK_PROJECTS = new HashSet<>();

	private final String project;
	private final Object HTTP_CLIENT_LOCK = new Object();
	private java.net.http.HttpClient plainHttpClient =
			java.net.http.HttpClient.newHttpClient();
	private java.net.http.HttpClient mtlsHttpClient = null;
	private String mtlsClientConfigKey = null;

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
		OutgoingEndpoint endpoint;
		try {
			endpoint = getOutgoingEndpoint();
		} catch (IllegalArgumentException ex) {
			logger.error("Detox queue send failed: recordId={}, invalid endpoint config: {}",
					recordId, ex.getMessage());
			return false;
		}
		Integer onsId = null;
		if (endpoint.requireOnsLookup) {
			onsId = findOnsId(ssaId);
		}
		if (ssaId == null || ssaId.isBlank()) {
			logger.error("Detox queue send failed: recordId={}, missing user",
					recordId);
			return false;
		}
		if (endpoint.requireOnsLookup && onsId == null) {
			logger.error("Detox queue send failed: recordId={}, no ONS mapping for ssaId={}",
					recordId, ssaId);
			return false;
		}
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
				logger.info("Detox queue sent: recordId={}, endpoint={}, ssaId={}, onsId={}",
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
			logger.error("Detox queue send failed: recordId={}",
					recordId, ex);
		} catch (InterruptedException ex) {
			logger.error("Detox queue send interrupted: recordId={}",
					recordId, ex);
			Thread.currentThread().interrupt();
		}
		return false;
	}

	private OutgoingEndpoint getOutgoingEndpoint() {
		if (USE_ONS_ENDPOINT) {
			return new OutgoingEndpoint("ons", URI.create(ONS_URL), true, true);
		}
		return new OutgoingEndpoint("local", URI.create(LOCAL_URL), false,
				false);
	}

	private java.net.http.HttpClient getHttpClient(OutgoingEndpoint endpoint)
			throws GeneralSecurityException, IOException {
		if (!endpoint.requireMtls)
			return plainHttpClient;
		String certPath = ONS_MTLS_CERT_PATH;
		String keyPath = ONS_MTLS_KEY_PATH;
		if (certPath == null || certPath.isBlank() ||
				keyPath == null || keyPath.isBlank()) {
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

	private static class OutgoingEndpoint {
		private final String mode;
		private final URI url;
		private final boolean requireMtls;
		private final boolean requireOnsLookup;

		public OutgoingEndpoint(String mode, URI url, boolean requireMtls,
				boolean requireOnsLookup) {
			this.mode = mode;
			this.url = url;
			this.requireMtls = requireMtls;
			this.requireOnsLookup = requireOnsLookup;
		}
	}
}
