package lambdify.mojo;

import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.apigateway.AmazonApiGatewayClient;
import com.amazonaws.services.lambda.AWSLambdaClient;
import com.amazonaws.services.lambda.model.ResourceNotFoundException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import lombok.experimental.var;
import lombok.val;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/**
 *
 */
@Mojo( name = "deploy-on-aws-lambda", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME )
public class LambdaDeployerMojo extends AbstractMojo {

	final AWSCredentialsProviderChain credentials = DefaultAWSCredentialsProviderChain.getInstance();
	final AWS aws = new AWS();

    @Parameter( defaultValue = "${project}", required = true)
    MavenProject project;

	@Parameter( defaultValue = "true", required = true )
	Boolean enabled;

	@Parameter( defaultValue = "true", required = true )
	Boolean apiGatewayEnabled;

    @Parameter( defaultValue = "${project.groupId}-${project.artifactId}-${project.version}", required = true )
    String apiGatewayName;

    @Parameter( defaultValue = "*", required = true )
    String apiGatewayEndpoint;

	@Parameter( defaultValue = "false", required = true )
	Boolean apiGatewayForceRecreate;

	@Parameter( defaultValue = "us-east-1", required = true )
	String regionName;

	@Parameter( defaultValue = "60", required = true )
	Integer lambdaTimeout;

	@Parameter( defaultValue = "128", required = true )
	Integer lambdaMemory;

	@Parameter( defaultValue = "${project.groupId}-${project.artifactId}-${project.version}", required = true )
	String lambdaFunctionName;

    @Parameter( defaultValue = "false", required = true )
    Boolean lambdaForceRecreate;

	@Parameter( defaultValue = "${project.build.directory}", required = true )
	File targetDirectory;

	@Parameter( defaultValue = "${project.build.finalName}.jar", required = true )
	String jarFileName;

	@Parameter( defaultValue = "${project.groupId}-${project.artifactId}-${project.version}", required = true )
	String s3Key;

	@Parameter( required = true, defaultValue = "UNDEFINED-S3-BUCKET" )
	String s3Bucket;

	@Parameter( required = true, defaultValue = "UNDEFINED-LAMBDA-ROLE" )
	String lambdaRole;

	@Parameter( required = true, defaultValue = "UNDEFINED-HANDLER-CLASS" )
	String handlerClass;

	@Override
	public void execute() throws MojoFailureException {
        if ( !project.getPackaging().equals( "jar" ) || !enabled ) return;

        getLog().info( "Lambdify Deployer" );
        configureAWS();

        val packageFile = getJarFile();
        checkIfPackageExists( packageFile );
        checkIfClassExists( packageFile, handlerClass );
		uploadPackage( packageFile );

		val parsedLambdaFunctionName = lambdaFunctionName.replaceAll( "[._]", "-" );
        val parsedApiGatewayName = apiGatewayName.replaceAll( "[._]", "-" );
		val lambdaFunction = setupLambdaFunction( parsedLambdaFunctionName );

		if (apiGatewayEnabled)
			createRestAPI( parsedLambdaFunctionName, parsedApiGatewayName, lambdaFunction );
	}

    private void checkIfPackageExists(File packageFile) throws MojoFailureException {
        if ( !packageFile.exists() )
            throw new MojoFailureException("Package not found: " + packageFile.getName());
    }

    private void checkIfClassExists(File packageFile, String handlerClass) throws MojoFailureException {
        try {
            getLog().info( "  >> Checking handler class '" + handlerClass + "'..." );
            val dirUrl = packageFile.toURI().toURL();
            val cl = new URLClassLoader(new URL[] {dirUrl}, getClass().getClassLoader());
            cl.loadClass( handlerClass );
        } catch (MalformedURLException e) {
            throw new MojoFailureException(e.getMessage());
        } catch (ClassNotFoundException e) {
            throw new MojoFailureException( "The specified class does not exists: " + handlerClass );
        }
    }

    private File getJarFile(){
		return new File( targetDirectory.getAbsolutePath() + File.separatorChar + jarFileName );
	}

	private void uploadPackage( File packageFile ) {
		getLog().info( "  >> Deploying package on AWS S3: " + s3Bucket + "/" + s3Key );
		val s3 = AmazonS3Client.builder().withCredentials( credentials )
				.withRegion( Regions.fromName(regionName) ).build();
		s3.putObject( s3Bucket, s3Key, packageFile );
	}

	private void configureAWS() {
		aws.lambda = AWSLambdaClient.builder().withCredentials( credentials ).withRegion( Regions.fromName( regionName ) ).build();
		aws.sts = AWSSecurityTokenServiceClient.builder().withCredentials( credentials ).withRegion( Regions.fromName( regionName ) ).build();
		aws.apiGateway = AmazonApiGatewayClient.builder().withCredentials( credentials ).withRegion( Regions.fromName( regionName ) ).build();
	}

	private String setupLambdaFunction(String lambdaFunctionName) {
		try {
		    if ( lambdaForceRecreate ) {
                getLog().info( "  >> Trying to remove previously created function '"+ lambdaFunctionName +"'. Reason: lambdaForceRecreate = true" );
                aws.deleteFunction( lambdaFunctionName );
            }

			return updateLambdaFunction( lambdaFunctionName );
		} catch ( ResourceNotFoundException cause ) {
			return createLambdaFunction( lambdaFunctionName );
		}
	}

	private String updateLambdaFunction( final String lambdaFunctionName ){
		val result = aws.getFunction( lambdaFunctionName );
		val functionArn = result.getConfiguration().getFunctionArn();
		getLog().info( "  >> Updating AWS Lambda Function '"+lambdaFunctionName+"'..." );
		aws.updateFunction( functionArn, s3Bucket, s3Key  );
		return functionArn;
	}

	private String createLambdaFunction( final String lambdaFunctionName ){
		getLog().info( "  >> Creating AWS Lambda Function '"+lambdaFunctionName+"'..." );
		val result = aws.createFunction(
            lambdaFunctionName, handlerClass, s3Bucket, s3Key, lambdaTimeout, lambdaMemory, lambdaRole );
		return result.getFunctionArn();
	}

	private void createRestAPI(String parsedProjectName, String parsedApiGatewayName, String lambdaFunction){
		var result = aws.getRestApi( parsedApiGatewayName );
		if ( result != null && apiGatewayForceRecreate) {
			getLog().info( "  >> Removing REST API '" + parsedProjectName + "' API Gateway. Reason: apiGatewayForceRecreate=true." );
			aws.deleteRestApi( result.getId() );
			result = null;
		}

        var restApiID = "";
        if ( result == null ) {
            getLog().info( "  >> Deploying a new REST API '"+ parsedApiGatewayName +"'..." );
            restApiID = aws.createRestApi( parsedApiGatewayName ).getId();
		} else {
            getLog().info( "  >> Updating REST API '"+ parsedApiGatewayName +"'..." );
            restApiID = result.getId();
        }

        val accountId = aws.getMyAccountId();
        setupApiGateway(accountId, restApiID, lambdaFunction, parsedProjectName);
	}

	private void setupApiGateway( String accountId, String restApiID, String functionArn, String projectName ) {
        getLog().info( "  >> Registering '" + apiGatewayEndpoint + "' endpoint..." );
	    pointApiGatewayRequestTo( restApiID, functionArn, apiGatewayEndpoint );

        getLog().info( "  >> Deploying the new version..." );
		aws.deployFunction(restApiID);
		val sourceArn = "arn:aws:execute-api:"+regionName+":"+accountId+":"+restApiID+"/*/*/*";
        getLog().info( "  >> Adding permission..." );
		aws.addPermissionToInvokeLambdaFunctions(projectName, sourceArn);
	}

    private void pointApiGatewayRequestTo(String restApiID, String functionArn, String apiGatewayEndpoint) {
        if ( apiGatewayEndpoint.equals("/*") ) {
            pointEveryApiGatewayRequestTo(restApiID, functionArn);
        } else if ( apiGatewayEndpoint.endsWith( "/*" ) ) {
            apiGatewayEndpoint = apiGatewayEndpoint.replaceFirst("/\\*$", "");
            createEndpointAndAssignFunction( restApiID, functionArn, apiGatewayEndpoint);
            createEndpointAndAssignFunction( restApiID, functionArn, apiGatewayEndpoint + "/{proxy+}");
        } else
            createEndpointAndAssignFunction(restApiID, functionArn, apiGatewayEndpoint);
    }

    private void createEndpointAndAssignFunction(String restApiID, String functionArn, String apiGatewayEndpoint ){
        var result = aws.createResourcePath( restApiID, apiGatewayEndpoint );
        if ( isEmpty( result.getResourceMethods() ) )
            aws.putMethod(restApiID, result.getId() );
        aws.assignLambdaToResource(restApiID, result.getId(), functionArn, regionName);
    }

    private boolean isEmpty(Map<?,?> result) {
        return result == null || result.isEmpty();
    }

    private void pointEveryApiGatewayRequestTo(String restApiID, String functionArn ){
        var resourceId = pointApiGatewayRequestsOnRootTo( restApiID, functionArn );
        resourceId = aws.createProxyResource(resourceId, restApiID).getId();
        aws.putMethod(restApiID, resourceId);
        aws.assignLambdaToResource(restApiID, resourceId, functionArn, regionName);
    }

    private String pointApiGatewayRequestsOnRootTo(String restApiID, String functionArn ){
        getLog().info( "  >> Registering '/'" );
        var resourceId = aws.getRootResourceId( restApiID );
        aws.putMethod(restApiID, resourceId);
        aws.assignLambdaToResource(restApiID, resourceId, functionArn, regionName);
        return resourceId;
    }
}
