package org.ops.netpulse.service;

import lombok.RequiredArgsConstructor;
import org.ops.netpulse.entity.SystemConfig;
import org.ops.netpulse.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigRepository repository;

    public Optional<String> getValue(String key) {
        return repository.findByConfigKey(key).map(SystemConfig::getConfigValue);
    }

    public List<SystemConfig> findAll() {
        return repository.findAll();
    }

    @Transactional
    public SystemConfig save(SystemConfig config) {
        return repository.findByConfigKey(config.getConfigKey())
                .map(existing -> {
                    existing.setConfigValue(config.getConfigValue());
                    existing.setRemark(config.getRemark());
                    return repository.save(existing);
                })
                .orElseGet(() -> repository.save(config));
    }

    @Transactional
    public void saveByKey(String key, String value) {
        repository.findByConfigKey(key)
                .ifPresentOrElse(
                        c -> { c.setConfigValue(value); repository.save(c); },
                        () -> repository.save(SystemConfig.builder().configKey(key).configValue(value).build())
                );
    }
}
