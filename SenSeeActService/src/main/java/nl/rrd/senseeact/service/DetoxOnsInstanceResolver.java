package nl.rrd.senseeact.service;

import nl.rrd.utils.AppComponents;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves ONS instance configuration for signup and outgoing queue delivery.
 */
public final class DetoxOnsInstanceResolver {
	private static final String DEFAULT_INSTANCE_ID = "default";
	private static final String LEGACY_DEFAULT_ONS_URL =
			"https://api-staging.ons.io/v0/openehr_dossier/back_channel/unauthorized/composition_wrappers";
	private static final String LEGACY_DEFAULT_MTLS_CERT_PATH =
			"/etc/senseeact/ons/client.pem";
	private static final String LEGACY_DEFAULT_MTLS_KEY_PATH =
			"/etc/senseeact/ons/client.key";
	private static final String ONS_ENDPOINT_PATH =
			"/v0/openehr_dossier/back_channel/unauthorized/composition_wrappers";

	private DetoxOnsInstanceResolver() {
	}

	public static OnsInstance resolveForRequestParameter(String onsInstanceParam) {
		Map<String,OnsInstance> instances = readConfiguredInstances();
		String requested = trimToNull(onsInstanceParam);
		if (requested == null)
			return resolveDefaultInstance(instances);
		if (instances.containsKey(requested))
			return instances.get(requested);
		String requestedUrl = normalizeInstanceUrl(requested);
		for (OnsInstance instance : instances.values()) {
			if (instance.getOnsInstanceUrl().equals(requestedUrl))
				return instance;
		}
		throw new IllegalArgumentException(
				"Unknown ONS instance: " + onsInstanceParam);
	}

	public static OnsInstance resolveForStoredInstance(String onsInstance) {
		Map<String,OnsInstance> instances = readConfiguredInstances();
		String stored = trimToNull(onsInstance);
		if (stored == null)
			return resolveDefaultInstance(instances);
		if (instances.containsKey(stored))
			return instances.get(stored);
		String storedUrl = null;
		try {
			storedUrl = normalizeInstanceUrl(stored);
		} catch (IllegalArgumentException ex) {
			return resolveDefaultInstance(instances);
		}
		for (OnsInstance instance : instances.values()) {
			if (instance.getOnsInstanceUrl().equals(storedUrl))
				return instance;
		}
		OnsInstance defaultInstance = resolveDefaultInstance(instances);
		return new OnsInstance("custom", storedUrl,
				defaultInstance.getMtlsCertPath(),
				defaultInstance.getMtlsKeyPath());
	}

	private static OnsInstance resolveDefaultInstance(
			Map<String,OnsInstance> instances) {
		Configuration config = AppComponents.get(Configuration.class);
		String defaultInstance = trimToNull(
				config.get(Configuration.DETOX_ONS_DEFAULT_INSTANCE));
		if (defaultInstance != null && instances.containsKey(defaultInstance))
			return instances.get(defaultInstance);
		if (instances.containsKey(DEFAULT_INSTANCE_ID))
			return instances.get(DEFAULT_INSTANCE_ID);
		return instances.values().iterator().next();
	}

	private static Map<String,OnsInstance> readConfiguredInstances() {
		Configuration config = AppComponents.get(Configuration.class);
		Map<String,OnsInstance> result = new LinkedHashMap<>();
		String instancesRaw = trimToNull(config.get(Configuration.DETOX_ONS_INSTANCES));
		if (instancesRaw != null) {
			String[] parts = instancesRaw.split(",");
			for (String part : parts) {
				String instanceId = trimToNull(part);
				if (instanceId == null)
					continue;
				OnsInstance instance = readConfiguredInstance(config, instanceId);
				if (instance != null)
					result.put(instanceId, instance);
			}
		}
		if (!result.isEmpty())
			return result;
		String onsUrl = trimToNull(config.get(Configuration.DETOX_OUTGOING_ONS_URL));
		if (onsUrl == null)
			onsUrl = LEGACY_DEFAULT_ONS_URL;
		String certPath = trimToNull(
				config.get(Configuration.DETOX_OUTGOING_MTLS_CERT_PATH));
		if (certPath == null)
			certPath = LEGACY_DEFAULT_MTLS_CERT_PATH;
		String keyPath = trimToNull(
				config.get(Configuration.DETOX_OUTGOING_MTLS_KEY_PATH));
		if (keyPath == null)
			keyPath = LEGACY_DEFAULT_MTLS_KEY_PATH;
		result.put(DEFAULT_INSTANCE_ID, new OnsInstance(DEFAULT_INSTANCE_ID,
				normalizeInstanceUrl(onsUrl), certPath, keyPath));
		return result;
	}

	private static OnsInstance readConfiguredInstance(Configuration config,
			String instanceId) {
		String base = Configuration.DETOX_ONS_INSTANCE_PREFIX + instanceId;
		String urlKey = base + Configuration.DETOX_ONS_INSTANCE_BASE_URL_SUFFIX;
		String certKey = base +
				Configuration.DETOX_ONS_INSTANCE_MTLS_CERT_PATH_SUFFIX;
		String keyKey = base +
				Configuration.DETOX_ONS_INSTANCE_MTLS_KEY_PATH_SUFFIX;
		String url = trimToNull(config.get(urlKey));
		if (url == null)
			return null;
		String certPath = trimToNull(config.get(certKey));
		String keyPath = trimToNull(config.get(keyKey));
		return new OnsInstance(instanceId, normalizeInstanceUrl(url), certPath,
				keyPath);
	}

	private static String normalizeInstanceUrl(String url) {
		String normalized = trimToNull(url);
		if (normalized == null) {
			throw new IllegalArgumentException("ONS instance URL is not defined");
		}
		URI uri;
		try {
			uri = URI.create(normalized);
		} catch (Exception ex) {
			throw new IllegalArgumentException("Invalid ONS instance URL");
		}
		String scheme = uri.getScheme();
		if (scheme == null)
			throw new IllegalArgumentException("Invalid ONS instance URL");
		String lowerScheme = scheme.toLowerCase();
		if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme))
			throw new IllegalArgumentException("Invalid ONS instance URL");
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("Invalid ONS instance URL");
		}
		if (normalized.endsWith("/") && normalized.length() > 1)
			normalized = normalized.substring(0, normalized.length() - 1);
		return normalized;
	}

	private static String trimToNull(String value) {
		if (value == null)
			return null;
		String result = value.trim();
		if (result.isEmpty())
			return null;
		return result;
	}

	public static class OnsInstance {
		private final String id;
		private final String onsInstanceUrl;
		private final String mtlsCertPath;
		private final String mtlsKeyPath;

		public OnsInstance(String id, String onsInstanceUrl, String mtlsCertPath,
				String mtlsKeyPath) {
			this.id = id;
			this.onsInstanceUrl = onsInstanceUrl;
			this.mtlsCertPath = mtlsCertPath;
			this.mtlsKeyPath = mtlsKeyPath;
		}

		public String getId() {
			return id;
		}

		public String getOnsInstanceUrl() {
			return onsInstanceUrl;
		}

		public String getMtlsCertPath() {
			return mtlsCertPath;
		}

		public String getMtlsKeyPath() {
			return mtlsKeyPath;
		}

		public boolean requiresMtls() {
			URI uri = URI.create(onsInstanceUrl);
			return "https".equalsIgnoreCase(uri.getScheme());
		}

		public URI getEndpointUri() {
			if (onsInstanceUrl.contains(ONS_ENDPOINT_PATH))
				return URI.create(onsInstanceUrl);
			return URI.create(onsInstanceUrl + ONS_ENDPOINT_PATH);
		}
	}
}
