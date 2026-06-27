package com.flatio.service.impl;

import com.flatio.domain.source.Source;
import com.flatio.repository.SourceRepository;
import com.flatio.service.SourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SourceServiceImpl implements SourceService {

  private final SourceRepository sourceRepository;

  @Override
  public Source findByCodeOrThrow(String code) {
    return sourceRepository.findByCode(code)
        .orElseThrow(() -> new IllegalStateException(
            "Source not registered in DB: " + code));
  }
}
