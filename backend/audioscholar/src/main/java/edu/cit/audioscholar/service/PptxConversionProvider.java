package edu.cit.audioscholar.service;

import edu.cit.audioscholar.model.AudioMetadata;

public interface PptxConversionProvider {

	PptxConversionResult convert(AudioMetadata metadata) throws Exception;
}
