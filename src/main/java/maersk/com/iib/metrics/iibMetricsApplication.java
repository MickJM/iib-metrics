package maersk.com.iib.metrics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class iibMetricsApplication {

	public static void main(String[] args) {
		SpringApplication sa = new SpringApplication(iibMetricsApplication.class);
		sa.run(args);

	}
}
