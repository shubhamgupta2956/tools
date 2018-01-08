/**
 * Copyright (c) 2011 Source Auditor Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.spdx.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.spdx.compare.LicenseCompareHelper;
import org.spdx.compare.SpdxCompareException;
import org.spdx.html.InvalidLicenseTemplateException;
import org.spdx.licensexml.XmlLicenseProvider;
import org.spdx.rdfparser.InvalidSPDXAnalysisException;
import org.spdx.rdfparser.license.ISpdxListedLicenseProvider;
import org.spdx.rdfparser.license.LicenseException;
import org.spdx.rdfparser.license.LicenseRestrictionException;
import org.spdx.rdfparser.license.SpdxListedLicense;
import org.spdx.rdfparser.license.SpdxListedLicenseException;
import org.spdx.spdxspreadsheet.SPDXLicenseSpreadsheet;
import org.spdx.spdxspreadsheet.SPDXLicenseSpreadsheet.DeprecatedLicenseInfo;
import org.spdx.tools.licensegenerator.FsfLicenseDataParser;
import org.spdx.tools.licensegenerator.ILicenseFormatWriter;
import org.spdx.tools.licensegenerator.ILicenseTester;
import org.spdx.tools.licensegenerator.LicenseHtmlFormatWriter;
import org.spdx.tools.licensegenerator.LicenseJsonFormatWriter;
import org.spdx.tools.licensegenerator.LicenseMarkdownFormatWriter;
import org.spdx.tools.licensegenerator.LicenseRdfFormatWriter;
import org.spdx.tools.licensegenerator.LicenseRdfaFormatWriter;
import org.spdx.tools.licensegenerator.LicenseTemplateFormatWriter;
import org.spdx.tools.licensegenerator.LicenseTextFormatWriter;
import org.spdx.tools.licensegenerator.SimpleLicenseTester;
import org.spdx.tools.licensegenerator.SpdxWebsiteFormatWriter;
import org.spdx.spdxspreadsheet.SpreadsheetException;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import au.com.bytecode.opencsv.CSVReader;

/**
 * Converts input license text and metadata into various output formats.
 * 
 * Supported input formats:
 *  - Spreadsheet - spreadsheet used by the SPDX legal team including associated text files
 *  - Directory of XML files - Files following the SPDX legal team license format
 * 
 * Supported output formats:
 *  - Text - license text
 *  - Templates - license templates as defined by the SPDX legal team matching guidelines
 *  - JSON - Json format as defined in https://github.com/spdx/license-list-data
 *  - RDFa - HTML with RDFa tags as defined in https://github.com/spdx/license-list-data
 *  - RDF NT - RDF NT format defined by the SPDX Spec
 *  - RDF XML - RDF XML format defined by the SPDX Spec
 *  - RDF Turtle - RDF Turtle format defined by the SPDX Spec
 *  - Website - the content for the website available at https://spdx.org/licenses
 *  
 *  Output generated by this tool can be found at https://github.com/spdx/license-list-data and on the 
 *  spdx.org licenses website
 *  
 *  To add a new output format, create a class supporting the ILicenseFormatWriter interface and add it
 *  to the writers list.
 *  
 * @author Gary O'Neall
 *
 */
public class LicenseRDFAGenerator {
		
	static final Set<Character> INVALID_TEXT_CHARS = Sets.newHashSet();
	
	static {
		INVALID_TEXT_CHARS.add('\uFFFD');
	}
	static int MIN_ARGS = 2;
	static int MAX_ARGS = 6;

	static final int ERROR_STATUS = 1;
	static final int WARNING_STATUS = 64;
	static final String CSS_TEMPLATE_FILE = "resources/screen.css";
	static final String CSS_FILE_NAME = "screen.css";
	static final String SORTTABLE_JS_FILE = "resources/sorttable.js";
	static final String SORTTABLE_FILE_NAME = "sorttable.js";
	static final String TEXT_FOLDER_NAME = "text";
	static final String TEMPLATE_FOLDER_NAME = "template";
	static final String HTML_FOLDER_NAME = "html";
	static final String RDFA_FOLDER_NAME = "rdfa";
	static final String JSON_FOLDER_NAME = "json";
	private static final String WEBSITE_FOLDER_NAME = "website";
	private static final String RDFXML_FOLDER_NAME = "rdfxml";
	private static final String RDFTURTLE_FOLDER_NAME = "rdfturtle";
	private static final String RDFNT_FOLDER_NAME = "rdfnt";
	private static final String TABLE_OF_CONTENTS_FILE_NAME = "licenses.md";
	
	/**
	 * @param args Arg 0 is either an input spreadsheet or a directory of licenses in XML format, arg 1 is the directory for the output html files
	 */
	public static void main(String[] args) {
		if (args == null || args.length < MIN_ARGS || args.length > MAX_ARGS) {
			System.out.println("Invalid arguments");
			usage();
			System.exit(ERROR_STATUS);
		}
		File ssFile = new File(args[0]);
		if (!ssFile.exists()) {
			System.out.println("Spreadsheet file "+ssFile.getName()+" does not exist");
			usage();
			System.exit(ERROR_STATUS);
		}
		File dir = new File(args[1]);
		if (!dir.exists()) {
			System.out.println("Output directory "+dir.getName()+" does not exist");
			usage();
			System.exit(ERROR_STATUS);
		}
		if (!dir.isDirectory()) {
			System.out.println("Output directory "+dir.getName()+" is not a directory");
			usage();
			System.exit(ERROR_STATUS);
		}
		String version = null;
		if (args.length > 2) {
			version = args[2];
		}
		String releaseDate = null;
		if (args.length > 3) {
			releaseDate = args[3];
		}
		File testFileDir = null;
		if (args.length > 4) {
			testFileDir = new File(args[4]);
			if (!testFileDir.exists()) {
				System.out.println("License test directory "+testFileDir.getName()+" does not exist");
				usage();
				System.exit(ERROR_STATUS);
			}
			if (!testFileDir.isDirectory()) {
				System.out.println("License test directory "+testFileDir.getName()+" is not a directory");
				usage();
				System.exit(ERROR_STATUS);
			}
		}
		String[] ignoredWarnings = new String[0];
		if (args.length > 5) {
			CSVReader reader = null;
			try {
				File warningsFile = new File(args[5]);
				if (warningsFile.exists()) {
					reader = new CSVReader(new FileReader(warningsFile));
				} else {
					reader = new CSVReader(new StringReader(args[5]));
				}
				ignoredWarnings = reader.readNext();
			} catch (IOException e) {
				System.out.println("IO Error reading ignored errors: "+e.getMessage());
				System.exit(ERROR_STATUS);
			} finally {
				if (reader != null) {
					try {
						reader.close();
					} catch (IOException e) {
						System.out.println("IO Error closing ignored errors string: "+e.getMessage());
						System.exit(ERROR_STATUS);
					}
				}
			}
		}
		try {
			List<String> warnings = generateLicenseData(ssFile, dir, version, releaseDate, testFileDir);
			if (warnings != null && warnings.size() > 0) {
				int numUnexpectedWarnings = warnings.size();
				for (String warning:warnings) {
					boolean ignore = false;
					for (String ignoreStr:ignoredWarnings) {
						if (warning.equalsIgnoreCase(ignoreStr)) {
							ignore = true;
							System.out.println("Ignoring warning '"+ignoreStr+"'");
							break;
						}
					}
					if (ignore) {
						numUnexpectedWarnings--;
					}
				}
				if (numUnexpectedWarnings > 0) {
					System.exit(WARNING_STATUS);
				}
			}
		} catch (LicenseGeneratorException e) {
			System.out.println(e.getMessage());
			System.exit(ERROR_STATUS);
		}
	}
	/**
	 * Generate license data
	 * @param ssFile Either a license spreadsheet file or a directory containing license XML files
	 * @param dir Output directory for the generated results
	 * @param version Version for the license lise
	 * @param releaseDate Release data string for the license
	 * @param testFileDir Directory of license text to test the generated licenses against
	 * @return warnings
	 * @throws LicenseGeneratorException 
	 */
	public static List<String> generateLicenseData(File ssFile, File dir,
			String version, String releaseDate, File testFileDir) throws LicenseGeneratorException {
		List<String> warnings = Lists.newArrayList();
		List<ILicenseFormatWriter> writers = Lists.newArrayList();
		ISpdxListedLicenseProvider licenseProvider = null;
		try {
			if (ssFile.getName().toLowerCase().endsWith(".xls")) {
				SPDXLicenseSpreadsheet licenseSpreadsheet = new SPDXLicenseSpreadsheet(ssFile, false, true);
				licenseProvider = licenseSpreadsheet;
				if (version == null || version.trim().isEmpty()) {
					version = licenseSpreadsheet.getLicenseSheet().getVersion();
				}
				if (releaseDate == null || releaseDate.trim().isEmpty()) {
					releaseDate = licenseSpreadsheet.getLicenseSheet().getReleaseDate();
				}
			} else if (ssFile.isDirectory()) {
				licenseProvider = new XmlLicenseProvider(ssFile);
			} else {
				throw new LicenseGeneratorException("Unsupported file format.  Must be a .xls file");
			}
			File textFolder = new File(dir.getPath() + File.separator +  TEXT_FOLDER_NAME);
			if (!textFolder.isDirectory() && !textFolder.mkdir()) {
				throw new LicenseGeneratorException("Error: text folder is not a directory");
			}
			writers.add(new LicenseTextFormatWriter(textFolder));
			File templateFolder = new File(dir.getPath() + File.separator +  TEMPLATE_FOLDER_NAME);
			if (!templateFolder.isDirectory() && !templateFolder.mkdir()) {
				throw new LicenseGeneratorException("Error: template folder is not a directory");
			}
			writers.add(new LicenseTemplateFormatWriter(templateFolder));
			File htmlFolder = new File(dir.getPath() + File.separator +  HTML_FOLDER_NAME);
			if (!htmlFolder.isDirectory() && !htmlFolder.mkdir()) {
				throw new LicenseGeneratorException("Error: HTML folder is not a directory");
			}
			writers.add(new LicenseHtmlFormatWriter(version, releaseDate, htmlFolder));
			File rdfaFolder = new File(dir.getPath() + File.separator +  RDFA_FOLDER_NAME);
			if (!rdfaFolder.isDirectory() && !rdfaFolder.mkdir()) {
				throw new LicenseGeneratorException("Error: RDFa folder is not a directory");
			}
			writers.add(new LicenseRdfaFormatWriter(version, releaseDate, rdfaFolder));	// Note: RDFa format is the same as the HTML
			File jsonFolder = new File(dir.getPath() + File.separator +  JSON_FOLDER_NAME);
			if (!jsonFolder.isDirectory() && !jsonFolder.mkdir()) {
				throw new LicenseGeneratorException("Error: JSON folder is not a directory");
			}
			File jsonFolderDetails = new File(dir.getPath() + File.separator +  JSON_FOLDER_NAME+ File.separator + "details");
			if (!jsonFolderDetails.isDirectory() && !jsonFolderDetails.mkdir()) {
				throw new LicenseGeneratorException("Error: JSON folder is not a directory");
			}
			File jsonFolderExceptions = new File(dir.getPath() + File.separator +  JSON_FOLDER_NAME + File.separator + "exceptions");
			if (!jsonFolderExceptions.isDirectory() && !jsonFolderExceptions.mkdir()) {
				throw new LicenseGeneratorException("Error: JSON folder is not a directory");
			}
			writers.add(new LicenseJsonFormatWriter(version, releaseDate, jsonFolder, jsonFolderDetails, jsonFolderExceptions));
			File website = new File(dir.getPath() + File.separator +  WEBSITE_FOLDER_NAME);
			if (!website.isDirectory() && !website.mkdir()) {
				throw new LicenseGeneratorException("Error: Website folder is not a directory");
			}
			writers.add(new SpdxWebsiteFormatWriter(version, releaseDate, website));
			File rdfXml = new File(dir.getPath() + File.separator +  RDFXML_FOLDER_NAME);
			if (!rdfXml.isDirectory() && !rdfXml.mkdir()) {
				throw new LicenseGeneratorException("Error: RdfXML folder is not a directory");
			}
			File rdfTurtle = new File(dir.getPath() + File.separator +  RDFTURTLE_FOLDER_NAME);
			if (!rdfTurtle.isDirectory() && !rdfTurtle.mkdir()) {
				throw new LicenseGeneratorException("Error: RDF Turtle folder is not a directory");
			}
			File rdfNt = new File(dir.getPath() + File.separator +  RDFNT_FOLDER_NAME);
			if (!rdfNt.isDirectory() && !rdfNt.mkdir()) {
				throw new LicenseGeneratorException("Error: RDF NT folder is not a directory");
			}
			writers.add(new LicenseRdfFormatWriter(rdfXml, rdfTurtle, rdfNt));
			File markdownFile = new File(dir.getPath() + File.separator +  TABLE_OF_CONTENTS_FILE_NAME);
			if (!markdownFile.isFile() && !markdownFile.createNewFile()) {
				throw new LicenseGeneratorException("Error: Unable to create markdown file");
			}
			writers.add(new LicenseMarkdownFormatWriter(version, releaseDate, markdownFile));
			ILicenseTester tester = null;
			if (testFileDir != null) {
				tester = new SimpleLicenseTester(testFileDir);
			}
			System.out.print("Processing License List");
			writeLicenseList(version, releaseDate, licenseProvider, warnings, writers, tester);
			System.out.println();
			System.out.print("Processing Exceptions");
			writeExceptionList(version, releaseDate, licenseProvider, warnings, writers, tester);
			System.out.println();
			System.out.print("Writing table of contents");
			for (ILicenseFormatWriter writer : writers) {
				writer.writeToC();
			}
			writeCssFile(website);
			writeSortTableFile(website);
			System.out.println();
			warnings.addAll(licenseProvider.getWarnings());
			if (warnings.size() > 0) {
				System.out.println("The following warning(s) were identified:");
				for (String warning : warnings) {
					System.out.println("\t"+warning);
				}
			}
			System.out.println("Completed processing licenses");
			return warnings;
		} catch (SpreadsheetException e) {
			throw new LicenseGeneratorException("\nInvalid spreadsheet: "+e.getMessage(),e);
		} catch (SpdxListedLicenseException e) {
			throw new LicenseGeneratorException("\nError reading standard licenses: "+e.getMessage(),e);
		} catch (LicenseGeneratorException e) {
			throw(e);
		} catch (Exception e) {
			throw new LicenseGeneratorException("\nUnhandled exception generating html: "+e.getMessage(),e);
		} finally {
			if (licenseProvider != null && (licenseProvider instanceof SPDXLicenseSpreadsheet)) {
				try {
					SPDXLicenseSpreadsheet spreadsheet = (SPDXLicenseSpreadsheet)licenseProvider;
					spreadsheet.close();
				} catch (SpreadsheetException e) {
					System.out.println("Error closing spreadsheet file: "+e.getMessage());
				}
			}
		}
	}
	
	/**
	 * @param version License list version
	 * @param releaseDate release date for the license list
	 * @param licenseProvider Provides the licensing information
	 * @param warnings Populated with any warnings if they occur
	 * @param writers License Format Writers to handle the writing for the different formats
	 * @param tester License tester used to test the results of licenses
	 * @throws IOException 
	 * @throws SpreadsheetException 
	 * @throws LicenseRestrictionException 
	 * @throws LicenseGeneratorException 
	 * @throws InvalidLicenseTemplateException 
	*/
	private static void writeExceptionList(String version, String releaseDate,
			ISpdxListedLicenseProvider licenseProvider, List<String> warnings, List<ILicenseFormatWriter> writers,
			ILicenseTester tester) throws IOException, LicenseRestrictionException, SpreadsheetException, LicenseGeneratorException, InvalidLicenseTemplateException {
		// Collect license ID's to check for any duplicate ID's being used (e.g. license ID == exception ID)
		Set<String> licenseIds = Sets.newHashSet();
		try {
			Iterator<SpdxListedLicense> licIter = licenseProvider.getLicenseIterator();
			while (licIter.hasNext()) {
				licenseIds.add(licIter.next().getLicenseId());
			}	
		} catch (SpdxListedLicenseException e) {
			System.out.println("Warning - Not able to check for duplicate license and exception ID's");
		}
		
		Iterator<LicenseException> exceptionIter = licenseProvider.getExceptionIterator();
		Map<String, String> addedExceptionsMap = Maps.newHashMap();
		while (exceptionIter.hasNext()) {
			System.out.print(".");
			LicenseException nextException = exceptionIter.next();
			addExternalMetaData(nextException);
			if (nextException.getLicenseExceptionId() != null && !nextException.getLicenseExceptionId().isEmpty()) {
				// check for duplicate exceptions
				Iterator<Entry<String, String>> addedExceptionIter = addedExceptionsMap.entrySet().iterator();
				while (addedExceptionIter.hasNext()) {
					Entry<String, String> entry = addedExceptionIter.next();
					if (entry.getValue().trim().equals(nextException.getLicenseExceptionText().trim())) {
						warnings.add("Duplicates exceptions: "+nextException.getLicenseExceptionId()+", "+entry.getKey());
					}
				}
				// check for a license ID with the same ID as the exception
				if (licenseIds.contains(nextException.getLicenseExceptionId())) {
					warnings.add("A license ID exists with the same ID as an exception ID: "+nextException.getLicenseExceptionId());
				}
				checkText(nextException.getLicenseExceptionText(), 
						"License Exception Text for "+nextException.getLicenseExceptionId(), warnings);
				addedExceptionsMap.put(nextException.getLicenseExceptionId(), nextException.getLicenseExceptionText());
				for (ILicenseFormatWriter writer:writers) {
					writer.writeException(nextException, false, null);
				}
				if (tester != null) {
					List<String> testResults = tester.testException(nextException);
					if (testResults != null && testResults.size() > 0) {
						for (String testResult:testResults) {
							warnings.add("Test for exception "+nextException.getLicenseExceptionId() + " failed: "+testResult);
						}
					}
				}
				
			}
		}
	}
	
	/**
	 * Add any additional data to a license exception from external sources
	 * @param exception Exception with fields updated from external sources
	 */
	private static void addExternalMetaData(LicenseException exception) {
		// Currently, there is no data to add
	}
	
	/**
	 * Check text for invalid characters
	 * @param text Text to check
	 * @param textDescription Description of the text being check (this will be used to form warning messages)
	 * @param warnings Array list of warnings to add to if an problem is found with the text
	 */
	private static void checkText(String text, String textDescription,
			List<String> warnings) {
		BufferedReader reader = new BufferedReader(new StringReader(text));
		try {
			int lineNumber = 1;
			String line = reader.readLine();
			while (line != null) {
				for (int i = 0; i < line.length(); i++) {
					if (INVALID_TEXT_CHARS.contains(line.charAt(i))) {
						warnings.add("Invalid character in " + textDescription +
								" at line number " + String.valueOf(lineNumber) + 
								" \"" +line + "\" at character location "+String.valueOf(i));
					}
				}
				lineNumber++;
				line = reader.readLine();
			}
		} catch (IOException e) {
			warnings.add("IO error reading text");
		} finally {
			try {
				reader.close();
			} catch (IOException e) {
				warnings.add("IO Error closing string reader");
			}
		}
	}
	
	/**
	 * Formats and writes the license list data
	 * @param version License list version
	 * @param releaseDate License list release date
	 * @param licenseProvider Provides the licensing information
	 * @param warnings Populated with any warnings if they occur
	 * @param writers License Format Writers to handle the writing for the different formats
	 * @param tester license tester to test the results of each license added
	 * @throws LicenseGeneratorException
	 * @throws InvalidSPDXAnalysisException
	 * @throws IOException
	 * @throws SpdxListedLicenseException
	 * @throws SpdxCompareException
	 */
	private static void writeLicenseList(String version, String releaseDate,
			ISpdxListedLicenseProvider licenseProvider, List<String> warnings,
			List<ILicenseFormatWriter> writers, ILicenseTester tester) throws LicenseGeneratorException, InvalidSPDXAnalysisException, IOException, SpdxListedLicenseException, SpdxCompareException {
		Iterator<SpdxListedLicense> licenseIter = licenseProvider.getLicenseIterator();
		Map<String, String> addedLicIdTextMap = Maps.newHashMap();	// keep track for duplicate checking
		while (licenseIter.hasNext()) {
			System.out.print(".");
			SpdxListedLicense license = licenseIter.next();
			addExternalMetaData(license);
			if (license.getLicenseId() != null && !license.getLicenseId().isEmpty()) {
				// Check for duplicate licenses
				Iterator<Entry<String, String>> addedLicenseTextIter = addedLicIdTextMap.entrySet().iterator();
				while (addedLicenseTextIter.hasNext()) {
					Entry<String, String> entry = addedLicenseTextIter.next();
					if (LicenseCompareHelper.isLicenseTextEquivalent(entry.getValue(), license.getLicenseText())) {
						warnings.add("Duplicates licenses: "+license.getLicenseId()+", "+entry.getKey());
					}
				}
				addedLicIdTextMap.put(license.getLicenseId(), license.getLicenseText());
				checkText(license.getLicenseText(), "License text for "+license.getLicenseId(), warnings);
				for (ILicenseFormatWriter writer : writers) {
					writer.writeLicense(license, false, null);
				}
				if (tester != null) {
					List<String> testResults = tester.testLicense(license);
					if (testResults != null && testResults.size() > 0) {
						for (String testResult:testResults) {
							warnings.add("Test for license "+license.getLicenseId() + " failed: "+testResult);
						}
					}
				}
			}
		}
		Iterator<DeprecatedLicenseInfo> depIter = licenseProvider.getDeprecatedLicenseIterator();
		while (depIter.hasNext()) {
			System.out.print(".");
			DeprecatedLicenseInfo deprecatedLicense = depIter.next();
			for (ILicenseFormatWriter writer : writers) {
				writer.writeLicense(deprecatedLicense.getLicense(), true, deprecatedLicense.getDeprecatedVersion());
			}
			if (tester != null) {
				List<String> testResults = tester.testLicense(deprecatedLicense.getLicense());
				if (testResults != null && testResults.size() > 0) {
					for (String testResult:testResults) {
						warnings.add("Test for license "+deprecatedLicense.getLicense().getLicenseId() + " failed: "+testResult);
					}
				}
			}
		}
	}

	/**
	 * Update license fields based on information from external metadata
	 * @param license
	 * @throws LicenseGeneratorException 
	 */
	private static void addExternalMetaData(SpdxListedLicense license) throws LicenseGeneratorException {
		license.setFsfLibre(FsfLicenseDataParser.getFsfLicenseDataParser().isSpdxLicenseFsfLibre(license.getLicenseId()));
	}
	
	/**
	 * Copy a file from the resources directory to a destination file
	 * @param resourceFileName filename of the file in the resources directory
	 * @param destination target file - warning, this will be overwritten
	 * @throws IOException 
	 */
	private static void copyResourceFile(String resourceFileName, File destination) throws IOException {
		File resourceFile = new File(resourceFileName);
		if (resourceFile.exists()) {
			Files.copy(resourceFile, destination);
		} else {
			InputStream is = LicenseRDFAGenerator.class.getClassLoader().getResourceAsStream(resourceFileName);
			InputStreamReader reader = new InputStreamReader(is);
			FileWriter writer = new FileWriter(destination);
			try {
				char[] buf = new char[2048];
				int len = reader.read(buf);
				while (len > 0) {
					writer.write(buf, 0, len);
					len = reader.read(buf);
				}
			} finally {
				if (writer != null) {
					writer.close();
				}
				reader.close();
			}
		}
	}

	private static void writeCssFile(File dir) throws IOException {
		File cssFile = new File(dir.getPath()+ File.separator + CSS_FILE_NAME);
		if (cssFile.exists()) {
			if (!cssFile.delete()) {
				throw(new IOException("Unable to delete old file"));
			}
		}
		copyResourceFile(CSS_TEMPLATE_FILE, cssFile);
	}
	
	private static void writeSortTableFile(File dir) throws IOException {
		File sortTableFile = new File(dir.getPath()+ File.separator + SORTTABLE_FILE_NAME);
		if (sortTableFile.exists()) {
			return;	// assume we don't need to create it
		}
		copyResourceFile(SORTTABLE_JS_FILE, sortTableFile);
	}
	
	private static void usage() {
		System.out.println("Usage:");
		System.out.println("LicenseRDFAGenerator input outputDirectory [version] [releasedate] [testfiles] [ignoredwarnings]");
		System.out.println("   Input - either a spreadsheet containing license information or a directory of license XML files");
		System.out.println("   outputDirectory - Directory to store the output from the license generator");
		System.out.println("   [version] - Version of the SPDX license list");
		System.out.println("   [releasedate] - Release date of the SPDX license list");
		System.out.println("   [testfiles] - Directory of original text files to compare the generated licenses against");
		System.out.println("   [ignoredwarnings] - Either a file name or a comma separated list of warnings to be ignored");
	}

}
