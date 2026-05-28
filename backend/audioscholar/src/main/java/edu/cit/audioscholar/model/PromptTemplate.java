package edu.cit.audioscholar.model;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PromptTemplate {
	private String templateId = UUID.randomUUID().toString();
	private String outputType;
	private String templateContent;
	private String description;
	private boolean active = true;
	private Date createdAt = new Date();
	private Date updatedAt = new Date();

	public String getTemplateId() {
		return templateId;
	}

	public void setTemplateId(String templateId) {
		this.templateId = templateId;
	}

	public String getOutputType() {
		return outputType;
	}

	public void setOutputType(String outputType) {
		this.outputType = outputType;
	}

	public String getTemplateContent() {
		return templateContent;
	}

	public void setTemplateContent(String templateContent) {
		this.templateContent = templateContent;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

	public Date getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Date updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("templateId", templateId);
		map.put("outputType", outputType);
		map.put("templateContent", templateContent);
		map.put("description", description);
		map.put("isActive", active);
		map.put("createdAt", createdAt);
		map.put("updatedAt", updatedAt);
		return map;
	}

	public static PromptTemplate fromMap(Map<String, Object> map) {
		if (map == null) {
			return null;
		}
		PromptTemplate template = new PromptTemplate();
		template.templateId = (String) map.get("templateId");
		template.outputType = (String) map.get("outputType");
		template.templateContent = (String) map.get("templateContent");
		template.description = (String) map.get("description");
		Object activeValue = map.get("isActive");
		if (activeValue instanceof Boolean booleanValue) {
			template.active = booleanValue;
		}
		return template;
	}
}
