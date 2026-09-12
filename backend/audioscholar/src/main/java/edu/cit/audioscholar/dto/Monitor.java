package edu.cit.audioscholar.dto;

import java.text.DecimalFormat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Monitor {

	@JsonProperty("id")
	private long id;

	@JsonProperty("friendly_name")
	private String friendlyName;

	@JsonProperty("url")
	private String url;

	@JsonProperty("status")
	private String status;

	@JsonProperty("custom_uptime_ratio")
	private String customUptimeRatio;

	@JsonProperty("currentStateDuration")
	private Long currentStateDuration;

	private static final DecimalFormat UPTIME_FORMAT = new DecimalFormat("0.000'%'");

	public long getId() {
		return id;
	}

	public void setId(long id) {
		this.id = id;
	}

	@JsonProperty("friendlyName")
	public void setFriendlyNameFromV3(String friendlyName) {
		this.friendlyName = friendlyName;
	}

	public String getFriendlyName() {
		return friendlyName;
	}

	public void setFriendlyName(String friendlyName) {
		this.friendlyName = friendlyName;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(Object status) {
		this.status = status != null ? status.toString() : null;
	}

	public String getCustomUptimeRatio() {
		return customUptimeRatio;
	}

	public void setCustomUptimeRatio(String customUptimeRatio) {
		this.customUptimeRatio = customUptimeRatio;
	}

	public Long getCurrentStateDuration() {
		return currentStateDuration;
	}

	public void setCurrentStateDuration(Long currentStateDuration) {
		this.currentStateDuration = currentStateDuration;
	}

	public String getStatusText() {
		return switch (normalizeStatus()) {
			case "0", "PAUSED" -> "Paused";
			case "1", "STARTED" -> "Not Checked Yet";
			case "2", "UP" -> "Up";
			case "8", "LOOKS_DOWN" -> "Seems Down";
			case "9", "DOWN" -> "Down";
			default -> "Unknown";
		};
	}

	public String getStatusColor() {
		return switch (normalizeStatus()) {
			case "2", "UP" -> "green";
			case "8", "9", "LOOKS_DOWN", "DOWN" -> "red";
			case "0", "PAUSED" -> "grey";
			case "1", "STARTED" -> "blue";
			default -> "orange";
		};
	}

	public String getFormattedUptimeRatio() {
		if (this.customUptimeRatio == null || this.customUptimeRatio.isEmpty()) {
			return "N/A";
		}
		try {
			double ratio = Double.parseDouble(this.customUptimeRatio);
			return UPTIME_FORMAT.format(ratio);
		} catch (NumberFormatException e) {
			return "N/A";
		}
	}

	private String normalizeStatus() {
		return status != null ? status.trim().toUpperCase() : "";
	}

	@Override
	public String toString() {
		return "Monitor{" + "id=" + id + ", friendlyName='" + friendlyName + '\'' + ", url='" + url + '\'' + ", status="
				+ status + " (" + getStatusText() + ")" + ", customUptimeRatio='" + customUptimeRatio + '\'' + '}';
	}
}
