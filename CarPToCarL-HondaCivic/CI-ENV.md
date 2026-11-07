CI environment normalization: the upstream gradle.properties contains a developer-local Windows org.gradle.java.home. CI removes only that local override and uses the configured Temurin JDK 17.
