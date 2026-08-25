# rootless-java-monorepo

HoundOS test fixture: a Java monorepo whose build files live in the module
directories (`services/payments`, `services/orders`) with **no root build file**.
Each module builds standalone with `mvn -f services/<name>/pom.xml compile`.
