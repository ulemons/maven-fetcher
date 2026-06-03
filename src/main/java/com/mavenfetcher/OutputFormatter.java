package com.mavenfetcher;

import java.util.List;

/**
 * Converts a list of {@link PackageInfo} objects into the SQL-values format
 * expected by the caller, e.g.:
 *
 * <pre>
 *   ('pkg:maven/org.springframework/spring-core', 'maven', 'org.springframework', 'spring-core', 1, 9856234, true),
 *   ('pkg:maven/org.apache.commons/commons-lang3', 'maven', 'org.apache.commons', 'commons-lang3', 2, 9723156, true),
 * </pre>
 *
 * Every row ends with a trailing comma (the file is meant to be embedded
 * inside a larger INSERT … VALUES (…) statement).
 */
public final class OutputFormatter {

    private OutputFormatter() {}

    /**
     * Formats every package on its own line.
     *
     * @param packages ordered list of packages (rank is taken from each object)
     * @return multi-line string ready to be written to a file
     */
    public static String format(List<PackageInfo> packages) {
        var sb = new StringBuilder();
        for (PackageInfo pkg : packages) {
            sb.append(String.format(
                "  ('%s', 'maven', '%s', '%s', %d, %d, true),\n",
                pkg.getPurl(),
                pkg.getGroupId(),
                pkg.getArtifactId(),
                pkg.getRank(),
                pkg.getDownloadCount()
            ));
        }
        return sb.toString();
    }
}
