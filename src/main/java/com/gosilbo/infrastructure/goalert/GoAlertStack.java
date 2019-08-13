package com.gosilbo.infrastructure.goalert;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.patterns.LoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.LoadBalancedFargateServiceProps;
import software.amazon.awscdk.services.ecs.patterns.LoadBalancerType;
import software.amazon.awscdk.services.elasticloadbalancingv2.ILoadBalancerV2;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;

import java.util.*;

public class GoAlertStack extends Stack {
    public GoAlertStack(final Construct parent, final String name, final StackProps properties, final String subDomain, final String domainName, final String ec2Ami, final String ec2InstanceType, final String mailgunKRSValue) {

        super(parent, name, properties);

        // Goalert Specific Values
        String goalertImage = "goalert/goalert";
        String goalertEncryptionKey = UUID.randomUUID().toString();

        // PostgreSQL database port - needed for providing ingress access.
        int databasePort = 5432;

        String ec2Region = properties.getEnv().getRegion();

        String ciderBlock = "10.0.0.0/16";
        VpcProps vpcProps = VpcProps.builder()
                .withCidr(ciderBlock)
                .withEnableDnsHostnames(true)
                .withEnableDnsSupport(true)
                .withMaxAzs(2)
                .withNatGateways(1)
                .build();
        Vpc vpc = new Vpc(this, "GoAlert-VPC", vpcProps);

        List<String> privateSubnetIds = new ArrayList<>();

        for (ISubnet subnet : vpc.getPrivateSubnets()) {
            privateSubnetIds.add(subnet.getSubnetId());
        }

        CfnDBCluster dbCluster = configureDatabase(vpc, databasePort, privateSubnetIds);

        configureUserCreationLambda(vpc, dbCluster, privateSubnetIds.get(0), ec2Region, ec2Ami, ec2InstanceType, goalertEncryptionKey, goalertImage);

        LoadBalancedFargateService loadBalancedFargateService = configureFargateService(dbCluster, goalertImage, goalertEncryptionKey, subDomain,  domainName, vpc);

        configureDNS(subDomain, domainName, loadBalancedFargateService, mailgunKRSValue);
    }

    private CfnDBCluster configureDatabase(Vpc vpc, int databasePort, List<String> privateSubnetIds) {
        SecurityGroup databaseSecurityGroup = new SecurityGroup(this, "goalert-database-security-group", SecurityGroupProps.builder()
                .withSecurityGroupName("goalert-database-security-group")
                .withVpc(vpc)
                .build());

        databaseSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(databasePort));
        databaseSecurityGroup.addIngressRule(Peer.anyIpv6(), Port.tcp(databasePort));

        CfnDBSubnetGroup subnetGroup = new CfnDBSubnetGroup(this, "GoAlert-DB-Subnets", CfnDBSubnetGroupProps.builder()
                .withDbSubnetGroupName("goalert-db-subnet-group")
                .withSubnetIds(privateSubnetIds)
                .withDbSubnetGroupDescription("GoAlert DB VPC Subnets")
                .build());

        return new CfnDBCluster(this, "GoAlert-Database-Cluster", CfnDBClusterProps.builder()
                .withEngine("aurora-postgresql")
                .withEngineMode("serverless")
                .withMasterUsername("postgres")
                .withMasterUserPassword("password")
                .withDatabaseName("goalert")
                .withVpcSecurityGroupIds(List.of(databaseSecurityGroup.getSecurityGroupId()))
                .withDbSubnetGroupName(subnetGroup.getRef())
                .withScalingConfiguration(CfnDBCluster.ScalingConfigurationProperty.builder()
                        .withAutoPause(true)
                        .withMinCapacity(2)
                        .withMaxCapacity(2)
                        .withSecondsUntilAutoPause(300)
                        .build())
                .build());
    }

    private void configureUserCreationLambda(Vpc vpc, CfnDBCluster dbCluster, String ec2SubnetId, String ec2Region, String ec2Ami, String ec2InstanceType, String goalertEncrptionKey, String goalertImage) {
        SecurityGroup lambdaSecurityGroup = new SecurityGroup(this, "goalert-lambda-user-security-group", SecurityGroupProps.builder()
                .withSecurityGroupName("goalert-lambda-user-security-group")
                .withVpc(vpc)
                .build());

        Map<String, Object> lambdaEnvironmentVariables = new HashMap<>();
        lambdaEnvironmentVariables.put("DB_HOSTNAME", dbCluster.getAttrEndpointAddress());
        lambdaEnvironmentVariables.put("DB_NAME", dbCluster.getDatabaseName());
        lambdaEnvironmentVariables.put("DB_USERNAME", dbCluster.getMasterUsername());
        lambdaEnvironmentVariables.put("DB_PASSWORD", dbCluster.getMasterUserPassword());
        lambdaEnvironmentVariables.put("EC2_SUBNET_ID", ec2SubnetId);
        lambdaEnvironmentVariables.put("EC2_REGION", ec2Region);
        lambdaEnvironmentVariables.put("EC2_AMI", ec2Ami);
        lambdaEnvironmentVariables.put("EC2_INSTANCE_TYPE", ec2InstanceType);
        lambdaEnvironmentVariables.put("GOALERT_ENCRYPTION_KEY", goalertEncrptionKey);
        lambdaEnvironmentVariables.put("GOALERT_DOCKER_IMAGE", goalertImage);

        List<PolicyStatement> policyStatementList = new ArrayList<>();

        policyStatementList.add(new PolicyStatement(PolicyStatementProps.builder()
                .withActions(new ArrayList<>(List.of("logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents",
                        "ec2:*")))
                .withEffect(Effect.ALLOW)
                .withResources(new ArrayList<>(List.of("*")))
                .build()));

        PolicyDocument lambdaAccessPolicy = new PolicyDocument(PolicyDocumentProps.builder().withStatements(policyStatementList).build());

        Map<String, PolicyDocument> lambdaPolicies = new HashMap<>();
        lambdaPolicies.put("goalert-user-create", lambdaAccessPolicy);

        IRole lambdaRole = new Role(this, "GoAlert-Lambda-User-Create-Role", RoleProps.builder()
                .withInlinePolicies(lambdaPolicies)
                .withAssumedBy(new ServicePrincipal("lambda", ServicePrincipalOpts.builder().build()))
                .build());

        SingletonFunction lambdaFunction = new SingletonFunction(this, "GoAlert-User-Create-Lambda",
                SingletonFunctionProps.builder()
                        .withDescription("Lambda which creates users in GoAlert")
                        .withRole(lambdaRole)
                        .withCode(
                                Code.inline(
                                        "#!/usr/bin/python\n" +
                                                "import boto3\n" +
                                                "import os\n" +
                                                "db_host = os.environ['DB_HOSTNAME']\n" +
                                                "db_name = os.environ['DB_NAME']\n" +
                                                "db_user = os.environ['DB_USERNAME']\n" +
                                                "db_pass = os.environ['DB_PASSWORD']\n" +
                                                "subnet_id = os.environ['EC2_SUBNET_ID']\n" +
                                                "REGION = os.environ['EC2_REGION']\n" +
                                                "AMI = os.environ['EC2_AMI']\n" +
                                                "INSTANCE_TYPE = os.environ['EC2_INSTANCE_TYPE']\n" +
                                                "goalert_encryption_key = os.environ['GOALERT_ENCRYPTION_KEY']\n" +
                                                "goalert_docker_image = os.environ['GOALERT_DOCKER_IMAGE']\n" +
                                                "EC2 = boto3.client('ec2', region_name=REGION)\n" +
                                                "def lambda_to_ec2(event, context):\n" +
                                                "   goalert_user_email = event['userEmail']\n" +
                                                "   goalert_user_id = event['userId']\n" +
                                                "   goalert_user_password = event['password']\n" +
                                                "   init_script = \"\"\"#!/bin/bash\n" +
                                                "apt-get update\n" +
                                                "apt-get install -y apt-transport-https ca-certificates curl software-properties-common\n" +
                                                "curl -fsSL https://download.docker.com/linux/ubuntu/gpg | apt-key add -\n" +
                                                "apt-key fingerprint 0EBFCD88\n" +
                                                "sudo add-apt-repository \\\n" +
                                                "   \"deb [arch=amd64] https://download.docker.com/linux/ubuntu \\\n" +
                                                "   $(lsb_release -cs) \\\n" +
                                                "   stable\"\n" +
                                                "apt-get update\n" +
                                                "apt-get install -y docker-ce\n" +
                                                "service docker start\n" +
                                                "docker run -d --name goalert_container  -e GOALERT_DB_URL=\"postgres://{0}:{1}@{2}/{3}\" -e GOALERT_DATA_ENCRYPTION_KEY=\"{4}\" {8}\n" +
                                                "end=$((SECONDS+120))\n" +
                                                "docker logs goalert_container\n" +
                                                "while [ $SECONDS -lt $end ]; do\n" +
                                                "   docker exec -d goalert_container goalert add-user --admin --email {5} --user {6} --pass {7} --db-url \"postgres://{0}:{1}@{2}/{3}\" --data-encryption-key \"{4}\";\n" +
                                                "   sleep 5;\n" +
                                                "done;\n" +
                                                "shutdown -h +1\"\"\"\n" +
                                                "   init_script_variables = init_script.format(db_user,db_pass,db_host,db_name,goalert_encryption_key,goalert_user_email,goalert_user_id,goalert_user_password, goalert_docker_image)\n" +
                                                "   instance = EC2.run_instances(\n" +
                                                "       ImageId=AMI,\n" +
                                                "       InstanceType=INSTANCE_TYPE,\n" +
                                                "       SubnetId=subnet_id,\n" +
                                                "       MinCount=1,\n" +
                                                "       MaxCount=1,\n" +
                                                "       InstanceInitiatedShutdownBehavior='terminate',\n" +
                                                "       UserData=init_script_variables\n" +
                                                "   )\n" +
                                                "   print 'New instance created.'\n" +
                                                "   instance_id = instance['Instances'][0]['InstanceId']\n" +
                                                "   print instance_id\n" +
                                                "   return instance_id"))
                        .withHandler("index.lambda_to_ec2")
                        .withFunctionName("GoAlert-Lambda-User-Create-Fnc")
                        .withTimeout(Duration.seconds(300))
                        .withRuntime(Runtime.PYTHON_2_7)
                        .withEnvironment(lambdaEnvironmentVariables)
                        .withUuid(UUID.randomUUID().toString())
                        .withVpc(vpc)
                        .withSecurityGroup(lambdaSecurityGroup)
                        .build()
        );

        new CfnOutput(this, "GoAlert-Lambda-User-Create-Fnc-Output", CfnOutputProps.builder()
                .withExportName("GoAlert-Lambda-User-Create-Fnc-Name")
                .withValue(lambdaFunction.getFunctionName())
                .build());
    }

    private LoadBalancedFargateService configureFargateService( CfnDBCluster dbCluster, String goalertImage, String goalertEncryptionKey, String subDomain, String domainName, Vpc vpc) {
        int goalertContainerPort = 80;

        Map<String, String> containerEnvironmentVariables = new HashMap<>();
        containerEnvironmentVariables.put("GOALERT_DB_URL", "postgres://"
                + dbCluster.getMasterUsername()
                + ":"
                + dbCluster.getMasterUserPassword()
                + "@"
                + dbCluster.getAttrEndpointAddress()
                + "/"
                + dbCluster.getDatabaseName());
        containerEnvironmentVariables.put("GOALERT_DATA_ENCRYPTION_KEY", goalertEncryptionKey);
        containerEnvironmentVariables.put("GOALERT_LISTEN", ":" + goalertContainerPort);

        ClusterProps clusterProps = ClusterProps.builder()
                .withClusterName("GoAlert_Cluster")
                .withVpc(vpc)
                .build();

        Cluster cluster = new Cluster(this, "GoAlert-Cluster", clusterProps);

        ContainerImage containerImage = ContainerImage.fromRegistry(goalertImage);

        CertificateProps certificateProps = CertificateProps.builder()
                .withDomainName(subDomain + "." + domainName)
                .build();

        Certificate certificate = new Certificate(this, "GoAlert-Certificate", certificateProps);

        LoadBalancedFargateServiceProps loadBalancedFargateServiceProps = LoadBalancedFargateServiceProps.builder()
                .withCluster(cluster)
                .withContainerName("GoAlert_Container")
                .withContainerPort(goalertContainerPort)
                .withEnvironment(containerEnvironmentVariables)
                .withImage(containerImage)
                .withServiceName("GoAlert_Service")
                .withCertificate(certificate)
                .withLoadBalancerType(LoadBalancerType.APPLICATION)
                .build();

        return new LoadBalancedFargateService(this, "GoAlert-Fargate", loadBalancedFargateServiceProps);
    }

    private void configureDNS(String subDomain, String domainName, LoadBalancedFargateService loadBalancedFargateService, String mailgunKRSValue) {
        IHostedZone hostedZone =  HostedZone.fromLookup(this, "HostedZone", HostedZoneProviderProps.builder()
                .withDomainName(domainName)
                .build());

        ARecord goalertRecord = new ARecord(this, "GoAlert-A-Record", ARecordProps.builder()
                .withRecordName(subDomain)
                .withTarget(new RecordTarget()
                        .fromAlias(new LoadBalancerTarget((ILoadBalancerV2) loadBalancedFargateService.getLoadBalancer())))
                .withZone(hostedZone)
                .withTtl(Duration.minutes(5))
                .build());

        new CfnOutput(this, "GoAlert-A-RecordSet-Output", CfnOutputProps.builder()
                .withExportName("GoAlert-A-RecordSet")
                .withValue(goalertRecord.getDomainName())
                .build());

        List<MxRecordValue> mxRecordValueList = new ArrayList<>();
        mxRecordValueList.add(MxRecordValue.builder().withHostName("mxa.mailgun.org").withPriority(10).build());
        mxRecordValueList.add(MxRecordValue.builder().withHostName("mxb.mailgun.org").withPriority(10).build());

        new MxRecord(this, "GoAlert-MX-RecordSet", MxRecordProps.builder()
                .withRecordName(subDomain)
                .withValues(mxRecordValueList)
                .withZone(hostedZone)
                .withTtl(Duration.minutes(5))
                .build());

        new RecordSet(this, "GoAlert-TXT-RecordSet", RecordSetProps.builder()
                .withRecordName(subDomain)
                .withTarget(new RecordTarget()
                        .fromValues("\"v=spf1 include:mailgun.org ~all\""))
                .withRecordType(RecordType.TXT)
                .withZone(hostedZone)
                .withTtl(Duration.minutes(5))
                .build());

        new RecordSet(this, "GoAlert-Mailgun-CName-RecordSet", RecordSetProps.builder()
                .withRecordName("email." + subDomain)
                .withTarget(new RecordTarget()
                        .fromValues("mailgun.org"))
                .withRecordType(RecordType.CNAME)
                .withZone(hostedZone)
                .withTtl(Duration.minutes(5))
                .build());

        new RecordSet(this, "GoAlert-Mailgun-KRS-TXT-RecordSet", RecordSetProps.builder()
                .withRecordName("krs._domainkey." + subDomain)
                .withTarget(new RecordTarget()
                        .fromValues(mailgunKRSValue))
                .withRecordType(RecordType.TXT)
                .withZone(hostedZone)
                .withTtl(Duration.minutes(5))
                .build());
    }
}
