package edu.cit.audioscholar.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PptxTextExtractorService {

	private static final Logger log = LoggerFactory.getLogger(PptxTextExtractorService.class);

	public String extractText(Path pptxPath) {
		if (pptxPath == null || !Files.exists(pptxPath)) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		try (InputStream inputStream = Files.newInputStream(pptxPath); XMLSlideShow slideShow = new XMLSlideShow(inputStream)) {
			int slideNumber = 1;
			for (XSLFSlide slide : slideShow.getSlides()) {
				StringBuilder slideText = new StringBuilder();
				for (XSLFShape shape : slide.getShapes()) {
					if (shape instanceof XSLFTextShape textShape && textShape.getText() != null) {
						slideText.append(textShape.getText()).append('\n');
					}
				}
				if (!slideText.isEmpty()) {
					text.append("Slide ").append(slideNumber).append(':').append('\n').append(slideText).append('\n');
				}
				slideNumber++;
			}
		} catch (IOException | RuntimeException e) {
			log.warn("Failed to extract PPTX text from {}. Continuing with audio-only context.", pptxPath, e);
			return "";
		}
		return text.toString().trim();
	}
}
