package billetera;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/**
	 * Además de esperar a que Postgres diga que está listo (dentro del contenedor), espera
	 * a que el puerto responda desde el Mac. Con Colima el puerto tarda unos segundos en
	 * abrirse afuera, y sin esta espera los tests fallaban con "Connection refused".
	 */
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
			.waitingFor(new WaitAllStrategy()
				.withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
				.withStrategy(Wait.forListeningPort())
				.withStartupTimeout(Duration.ofMinutes(2)));
	}

}
