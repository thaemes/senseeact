package nl.rrd.senseeact.client.model.detox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import nl.rrd.utils.json.JsonObject;

@JsonIgnoreProperties(ignoreUnknown=true)
public class DetoxOnsSignupResult extends JsonObject {
	private String ssaId;
	private String email;
	private String password;
	private String qrPayload;
	private String qrPngBase64;

	public DetoxOnsSignupResult() {
	}

	public String getSsaId() {
		return ssaId;
	}

	public void setSsaId(String ssaId) {
		this.ssaId = ssaId;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getQrPayload() {
		return qrPayload;
	}

	public void setQrPayload(String qrPayload) {
		this.qrPayload = qrPayload;
	}

	public String getQrPngBase64() {
		return qrPngBase64;
	}

	public void setQrPngBase64(String qrPngBase64) {
		this.qrPngBase64 = qrPngBase64;
	}
}
