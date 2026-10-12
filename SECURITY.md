# Reporting Security Vulnerabilities

Do not report a security vulnerability in a public issue, a pull request or a discussion.

## In CrucibleVM

Report it privately through GitHub: on the repository's
[Security](https://github.com/SiteNetSoft/crucible/security) tab, choose **Report a vulnerability**
([direct link](https://github.com/SiteNetSoft/crucible/security/advisories/new)). Say what is
affected, how to reproduce it, preferably with a proof of concept, and which CrucibleVM commit you
found it in. The report is seen by the maintainers only, and you will be credited in the advisory
unless you ask not to be.

A compiler that computes a wrong value can be a vulnerability, for example where it removes a bounds
or type check that should have held. If you are not sure whether a miscompile is one, report it
privately.

CrucibleVM is alpha and has no supported releases: a fix goes into `crucible/main`.

## In GraalVM

CrucibleVM is a fork of GraalVM Community Edition and contains its code. A vulnerability that is
also in the GraalVM Community Edition release CrucibleVM is based on belongs to GraalVM; report it
to Oracle as [GraalVM's security policy](https://github.com/oracle/graal/security/policy) says. If
you are not sure which it is, report it here and it will be passed on.

CrucibleVM does not support running untrusted or adversarial code, as GraalVM does not.
