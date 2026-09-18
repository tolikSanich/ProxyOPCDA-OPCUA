package com.opcproxy;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class RepairPassword {
    public static void main(String[] args) {
        System.setProperty("jasypt.encryptor.password", args[0]);
        var ctx = new SpringApplicationBuilder()
                .sources(Application.class).web(WebApplicationType.NONE)
                .run();
        var enc = ctx.getBean(StringEncryptor.class);
        System.out.println("ENC(" + enc.encrypt(args[1]) + ")");
        ctx.close();
    }
// java -jar app.jar <master> <plain-password>
}
