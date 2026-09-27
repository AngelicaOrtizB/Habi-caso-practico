package billetera;

import org.springframework.boot.SpringApplication;

public class TestBilleteraApplication {

	public static void main(String[] args) {
		SpringApplication.from(BilleteraApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
