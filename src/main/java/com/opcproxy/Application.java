package com.opcproxy;

import com.vaadin.flow.theme.aura.Aura;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.Security;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles.css") // Your custom styles
@Push
public class Application implements AppShellConfigurator{

    static {
        // Убираем старую версию провайдера, если она где-то загрузилась
        Security.removeProvider("BC");
        // Регистрируем современный BC
        Security.addProvider(new BouncyCastleProvider());
    }

    public static void main(String[] args) {

        SpringApplication.run(Application.class, args);
    }

}
