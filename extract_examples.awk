#!/usr/bin/awk -f
# Usage: awk -f extract_examples.awk src/birdbrain/Finch.java
# Extracts javadoc example code and writes into
# examples/Example1.java, examples/Example2.java, ...

BEGIN { inside=0; n=0; outfile=""; name="" }

# Start of an example block: lines like " * <pre>"
/^[[:space:]]*\*[[:space:]]*<pre>[[:space:]]*$/ {
    inside=1
    n++
    name = sprintf("Example%02d", n)
    outfile = sprintf("examples/%s.java", name)
    print "import birdbrain.Finch;" >> outfile
    print "public class " name " {" >> outfile
    print "  public static void main(String args[]) {" >> outfile
    next
}

# End of an example block: lines like " * </pre>"
/^[[:space:]]*\*[[:space:]]*<\/pre>[[:space:]]*$/ {
    inside=0
    print "  } // end of main" >> outfile
    print "} // end of " name >> outfile 
    close(outfile)
    outfile=""
    name=""
    next
}

# Lines inside the block
inside {
    line = $0
    # Remove the first leading "*" (and one following space if present)
    sub(/^[[:space:]]*\*\s?/, "", line)

    # Decode minimal HTML entities
    gsub(/&lt;/, "<", line)
    gsub(/&gt;/, ">", line)

    print "    " line >> outfile
    next
}

