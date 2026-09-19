package com.opcproxy.opcua;

import com.opcproxy.persistence.entity.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Разрешает effective sampling interval тега (ТЗ §5.3):
 *   per-tag override (Tag.uaSamplingIntervalMs)
 *   -> IntervalProfile.uaSamplingIntervalMs
 *   -> app.opcua.default-sampling-interval-ms.
 */
@Component
public class SamplingIntervalResolver {

    @Value("${app.opcua.default-sampling-interval-ms:500}")
    private double defaultSamplingMs;

    public double resolve(Tag tag) {
        if (tag == null) return defaultSamplingMs;
        if (tag.getUaSamplingIntervalMs() != null && tag.getUaSamplingIntervalMs() > 0) {
            return tag.getUaSamplingIntervalMs();
        }
        if (tag.getIntervalProfile() != null
                && tag.getIntervalProfile().getUaSamplingIntervalMs() != null
                && tag.getIntervalProfile().getUaSamplingIntervalMs() > 0) {
            return tag.getIntervalProfile().getUaSamplingIntervalMs();
        }
        return defaultSamplingMs;
    }
}