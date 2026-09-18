package com.opcproxy.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Глобальная конфигурация старого JInterop/Utgard OPC DA-стека.
 *
 * JInterop 2.1.8 получает часть параметров RPC/NTLM через
 * системные свойства JVM. Настройки должны быть применены
 * до первого создания OPC DA Server/JISession.
 */
@Slf4j
@Configuration
public class JInteropConfig {
    /** Таймаут сокета j-Interop, мс. */
    private static final String SOCKET_TIMEOUT_MS = "15000";

    @PostConstruct
    public void configure() {
        // Читается в Server.connect(): ограничивает зависание чтений
        // на мёртвом сокете и даёт медленным серверам время на активацию.
        System.setProperty("rpc.socketTimeout", SOCKET_TIMEOUT_MS);

        log.info("j-Interop configured: rpc.socketTimeout={} ms. "
                        + "NTLM signing/sealing is applied by patched JIComServer (session security).",
                SOCKET_TIMEOUT_MS);
//        /*
//         * Включает NTLM2/session security в старом RPC-стеке JInterop.
//         */
//        System.setProperty(
//                "rpc.ntlm.ntlm2",
//                "true"
//        );
//
//        /*
//         * Включает NTLM signing.
//         *
//         * В NtlmConnection.java это приводит к:
//         *
//         * Security.PROTECTION_LEVEL_INTEGRITY
//         */
//        System.setProperty(
//                "rpc.ntlm.sign",
//                "true"
//        );
//
//        /*
//         * Не включаем шифрование без необходимости.
//         *
//         * Privacy сильнее Integrity, но для текущего требования
//         * Windows достаточно уровня Packet Integrity.
//         */
//        System.clearProperty("rpc.ntlm.seal");
//
//        log.info(
//                "JInterop configured: rpc.ntlm.ntlm2=true, rpc.ntlm.sign=true"
//        );
    }
}