package com.gosilbo.infrastructure.goalert;

import software.amazon.awscdk.core.App;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.StackProps;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class GoAlertApplication {

	public static void main(String[] args) {

		App app = new App();

		// Global Variables
		String accountId = "700164244043";
		String subDomain = "goalert";
		String domain = "silboapp.com";

		// Region Specific Values
		String region = "us-east-1";
		String ec2Ami = "ami-0a57edc25c5a0837f";
		String ec2InstanceType = "t2.nano";

		// Mailgun KRS validation
		String mailgunKRSValue="\"k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDpb+xyVNTeCQeQMDK9HFC9SyEOhFzBEBTDhCCBBfyhhQYsyeZtAe9nkfAPdH6rvUrxunFmiCVnEeHrp053P/xWVRqXoXqnYAXC3SgP0X7nH9Gc6v7kDfEhte0anqsPUf8eXSpmh6ceYbm34W0aqi8169TTQFSFSPQnPQymfSjwfQIDAQAB\"";

		// AWS tags
		Map<String, String> tagMap = new HashMap<>();
		tagMap.put("project", "goalert");
		tagMap.put("group", "operations");
		tagMap.put("environment", "production");

		Environment environment = Environment.builder()
				.withAccount(accountId)
				.withRegion(region)
				.build();

		StackProps stackProps = StackProps.builder()
				.withEnv(environment)
				.withTags(tagMap)
				.build();

		new GoAlertStack(app, "GoAlert-CDK", stackProps, subDomain, domain, ec2Ami, ec2InstanceType, mailgunKRSValue);

	}

}
