package com.gosilbo.infrastructure.goalert;

import software.amazon.awscdk.core.App;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GoalertApplication {

	public static void main(String[] args) {

		App app = new App();

		new GoAlertStack(app, "GoAlert-CDK");

	}

}
