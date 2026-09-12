package edu.cit.audioscholar.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import edu.cit.audioscholar.dto.Monitor;
import edu.cit.audioscholar.service.UptimeRobotService;

@Controller
public class HomeController {

	private static final DateTimeFormatter STATUS_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
	private static final int NEXT_UPDATE_SECONDS = 480;

	private final UptimeRobotService uptimeRobotService;

	public HomeController(UptimeRobotService uptimeRobotService) {
		this.uptimeRobotService = uptimeRobotService;
	}

	@GetMapping("/")
	public String showStatusPage() {
		// Redirect to the external UptimeRobot status page
		return "redirect:https://stats.uptimerobot.com/pJIct6UIpu";
	}

	@GetMapping("/status")
	public String showLocalStatusPage(Model model) {
		List<Monitor> monitors = uptimeRobotService.getMonitors();

		model.addAttribute("monitors", monitors);
		model.addAttribute("lastUpdated", LocalDateTime.now().format(STATUS_TIMESTAMP_FORMAT));
		model.addAttribute("nextUpdateSeconds", NEXT_UPDATE_SECONDS);
		model.addAttribute("overallStatusText", getOverallStatusText(monitors));
		model.addAttribute("overallStatusSuffix", getOverallStatusSuffix(monitors));
		model.addAttribute("overallStatusColor", getOverallStatusColor(monitors));

		return "status";
	}

	private String getOverallStatusText(List<Monitor> monitors) {
		if (monitors == null || monitors.isEmpty()) {
			return "No systems";
		}
		return hasProblemMonitor(monitors) ? "Some systems" : "All systems";
	}

	private String getOverallStatusSuffix(List<Monitor> monitors) {
		if (monitors == null || monitors.isEmpty()) {
			return "Configured";
		}
		return hasProblemMonitor(monitors) ? "Degraded" : "Operational";
	}

	private String getOverallStatusColor(List<Monitor> monitors) {
		if (monitors == null || monitors.isEmpty()) {
			return "grey";
		}
		return hasProblemMonitor(monitors) ? "red" : "green";
	}

	private boolean hasProblemMonitor(List<Monitor> monitors) {
		return monitors.stream().anyMatch(monitor -> "red".equals(monitor.getStatusColor()));
	}
}
