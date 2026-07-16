package com.example.demo;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.load();
		setIfPresent(dotenv, "VWORLD_API_KEY");
		setIfPresent(dotenv, "MAIL_USERNAME");
		setIfPresent(dotenv, "MAIL_PASSWORD");
		setIfPresent(dotenv, "JWT_SECRET");
		setIfPresent(dotenv, "OPENAI_API_KEY");
		setIfPresent(dotenv, "GOOGLE_CLIENT_ID");
		setIfPresent(dotenv, "GOOGLE_CLIENT_SECRET");

		SpringApplication.run(DemoApplication.class, args);
	}

	private static void setIfPresent(Dotenv dotenv, String key) {
		String value = dotenv.get(key);
		if (value != null) {
			System.setProperty(key, value);
		}
	}

}
