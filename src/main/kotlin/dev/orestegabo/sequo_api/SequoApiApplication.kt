package dev.orestegabo.sequo_api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SequoApiApplication

fun main(args: Array<String>) {
	runApplication<SequoApiApplication>(*args)
}
