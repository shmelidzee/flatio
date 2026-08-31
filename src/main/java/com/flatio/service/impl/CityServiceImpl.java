package com.flatio.service.impl;

import com.flatio.domain.city.City;
import com.flatio.repository.CityRepository;
import com.flatio.service.CityService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link CityService}.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CityServiceImpl implements CityService {

  private final CityRepository cityRepository;

  @Override
  public List<City> searchByName(String query) {
    return cityRepository.findByNameRuContainingIgnoreCase(query);
  }

  @Override
  public List<City> findAll() {
    return cityRepository.findAllByOrderByNameRuAsc();
  }

  @Override
  public Optional<City> findNearestCity(BigDecimal latitude, BigDecimal longitude) {
    return cityRepository.findNearestCity(latitude, longitude);
  }

  @Override
  public boolean existsById(Long id) {
    return cityRepository.existsById(id);
  }

  @Override
  public Optional<City> findById(Long id) {
    return cityRepository.findById(id);
  }
}
