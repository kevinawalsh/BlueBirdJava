# ---- Config ----
JAR   := birdbrain-1.0hc.jar

# ---- Default target ----
.PHONY: all
all: compile jar docs

# ---- Compile sources to classes ----
.PHONY: compile
compile:
	# Compiling java files
	@mkdir -p build
	javac -Xlint:deprecation -Xlint:unchecked -d build src/birdbrain/*.java

# ---- Build JAR (includes .class and .java) ----
.PHONY: jar
jar: compile
	# Creating jar
	jar cf $(JAR) -C build .
	# Adding source files to jar
	jar uf $(JAR) -C src .

.PHONY: docs
docs:
	@mkdir -p docs
	javadoc -d docs -sourcepath src -public -quiet -noindex -notree -nohelp -nonavbar -notimestamp -noqualifier all src/birdbrain/Finch.java
	@echo "See: docs/birdbrain/Finch.html"

# ---- Clean ----
.PHONY: clean
clean:
	# Deleting build artifacts and jar
	rm -rf build $(JAR)

# ---- Convenience: list contents of the jar ----
.PHONY: list
list:
	@echo "Contents of $(JAR):"
	@jar tf $(JAR)

