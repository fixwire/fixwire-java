package com.example.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A small shop API reporting to Fixwire (see FixwireConfig for the setup). */
@SpringBootApplication
public class ShopApplication {
  public static void main(String[] args) {
    SpringApplication.run(ShopApplication.class, args);
  }
}
