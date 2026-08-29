# Source this file before building CrucibleVM:  source crucible/env.sh
export MX_HOME="${MX_HOME:-$HOME/tools/mx}"
export JAVA_HOME="${JAVA_HOME_CRUCIBLE:-$HOME/.mx/jdks/labsjdk-ce-latest-jvmci-25.3-b22_amd64}"
export PATH="$MX_HOME:$JAVA_HOME/bin:$PATH"
export MX_PYTHON="${MX_PYTHON:-python3}"
