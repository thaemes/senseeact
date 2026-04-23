package nl.rrd.senseeact.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DetoxMessageQueueOutgoingIT {
	private static final String PROP_OUTGOING_URL =
			"senseeact.detox.outgoing.url";
	private static final String PROP_OUTGOING_MTLS =
			"senseeact.detox.outgoing.mtls";

	@Test
	void sendsPayloadToOverrideEndpoint() throws Exception {
		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setResponseCode(200));
		server.start();
		try {
			String url = server.url("/ons").toString();
			System.setProperty(PROP_OUTGOING_URL, url);
			System.setProperty(PROP_OUTGOING_MTLS, "false");

			DetoxMessageQueueListener listener =
					new DetoxMessageQueueListener("detox", false);
			String payload = "{\"hello\":\"world\"}";

			Method method = DetoxMessageQueueListener.class.getDeclaredMethod(
					"sendProcessedToOns", String.class, String.class, int.class,
					String.class, String.class);
			method.setAccessible(true);
			boolean ok = (boolean) method.invoke(listener, "rid-1",
					"ssa-1", 123, null, payload);

			assertTrue(ok);

			RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
			assertNotNull(request);
			assertEquals("POST", request.getMethod());
			assertEquals(payload, request.getBody().readUtf8());
		} finally {
			System.clearProperty(PROP_OUTGOING_URL);
			System.clearProperty(PROP_OUTGOING_MTLS);
			server.shutdown();
		}
	}
}
