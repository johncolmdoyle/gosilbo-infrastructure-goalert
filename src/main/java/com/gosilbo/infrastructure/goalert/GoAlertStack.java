package com.gosilbo.infrastructure.goalert;

import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.LoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.LoadBalancedFargateServiceProps;

import java.util.HashMap;
import java.util.Map;

public class GoAlertStack extends Stack {
    public GoAlertStack(final Construct parent, final String name) {

        super(parent, name);

        int goalertContainerPort = 80;
        String goalertImage = "nginx";

        Map<String, String> containerEnvironmentVariables = new HashMap<>();
        containerEnvironmentVariables.put("Key Name", "Value");

        Vpc vpc = new Vpc(this, "GoAlert-VPC");

        ClusterProps clusterProps = ClusterProps.builder()
                .withClusterName("GoAlert_Cluster")
                .withVpc(vpc)
                .build();

        Cluster cluster = new Cluster(this, "GoAlert-Cluster", clusterProps);

        ContainerImage containerImage = ContainerImage.fromRegistry(goalertImage);

        LoadBalancedFargateServiceProps loadBalancedFargateServiceProps = LoadBalancedFargateServiceProps.builder()
                .withCluster(cluster)
                .withContainerName("GoAlert_Container")
                .withContainerPort(goalertContainerPort)
                .withEnvironment(containerEnvironmentVariables)
                .withImage(containerImage)
                .withServiceName("GoAlert_Service")
                .build();

        LoadBalancedFargateService loadBalancedFargateService = new LoadBalancedFargateService(this, "GoAlert-Fargate", loadBalancedFargateServiceProps);


    }
}
