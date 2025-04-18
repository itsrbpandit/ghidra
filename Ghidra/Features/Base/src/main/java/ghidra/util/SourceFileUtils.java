/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import ghidra.formats.gfilesystem.FSUtilities;
import ghidra.program.database.sourcemap.SourceFile;
import ghidra.program.database.sourcemap.SourceFileIdType;
import ghidra.program.model.listing.Program;
import ghidra.program.model.sourcemap.SourceMapEntry;

/**
 * A utility class for creating {@link SourceFile}s from native paths, e.g., windows paths.
 */
public class SourceFileUtils {

	private static HexFormat hexFormat = HexFormat.of();
	private static Pattern dirNameValidator = Pattern.compile("\\W");

	private SourceFileUtils() {
		// singleton class
	}

	/**
	 * Creates a {@link SourceFile} from {@code path} with id type {@link SourceFileIdType#NONE}
	 * and empty identifier.  The path will be transformed using 
	 * {@link FSUtilities#normalizeNativePath(String)} and then {@link URI#normalize}.
	 * 
	 * @param path path
	 * @return source file
	 */
	public static SourceFile getSourceFileFromPathString(String path) {
		return getSourceFileFromPathString(path, SourceFileIdType.NONE, null);
	}

	/**
	 * Creates a {@link SourceFile} from {@code path} with the provided id type and identifier.
	 * The path will be transformed using{@link FSUtilities#normalizeNativePath(String)} and 
	 * then {@link URI#normalize}.
	 * 
	 * @param path path
	 * @param idType id type
	 * @param identifier identifier
	 * @return source file
	 */
	public static SourceFile getSourceFileFromPathString(String path, SourceFileIdType idType,
			byte[] identifier) {
		String standardized = FSUtilities.normalizeNativePath(path);
		return new SourceFile(standardized, idType, identifier);
	}

	/**
	 * Converts a {@code long} value to an byte array of length 8.  The most significant byte
	 * of the long will be at position 0 of the resulting array.
	 * @param l long
	 * @return byte array
	 */
	public static byte[] longToByteArray(long l) {
		byte[] bytes = new byte[8];
		BigEndianDataConverter.INSTANCE.putLong(bytes, 0, l);
		return bytes;
	}

	/**
	 * Converts a byte array of length 8 to a {@code long} value.  The byte at position 0 
	 * of the array will be the most significant byte of the resulting long.
	 * @param bytes array to convert
	 * @return long
	 * @throws IllegalArgumentException if bytes.length != 8
	 */
	public static long byteArrayToLong(byte[] bytes) {
		if (bytes.length != 8) {
			throw new IllegalArgumentException("bytes must have length 8");
		}
		return BigEndianDataConverter.INSTANCE.getLong(bytes);
	}

	/**
	 * Converts a {@code String} of hexadecimal character to an array of bytes. An initial "0x"
	 * or "0X" is ignored, as is the case of the digits a-f.
	 * @param hexString String to convert
	 * @return byte array
	 */
	public static byte[] hexStringToByteArray(String hexString) {
		if (StringUtils.isBlank(hexString)) {
			return new byte[0];
		}
		if (hexString.startsWith("0x") || hexString.startsWith("0X")) {
			hexString = hexString.substring(2);
		}
		return hexFormat.parseHex(hexString);
	}

	/**
	 * Converts a byte array to a {@code String} of hexadecimal digits.
	 * @param bytes array to convert
	 * @return string
	 */
	public static String byteArrayToHexString(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return StringUtils.EMPTY;
		}
		return hexFormat.formatHex(bytes);
	}

	/**
	 * Returns a {@link SourceLineBounds} record containing the minimum and maximum mapped line
	 * for {@code sourceFile} in {@code program}.
	 * @param program program
	 * @param sourceFile source file
	 * @return source line bounds or null
	 */
	public static SourceLineBounds getSourceLineBounds(Program program, SourceFile sourceFile) {
		List<SourceMapEntry> entries =
			program.getSourceFileManager().getSourceMapEntries(sourceFile, 0, Integer.MAX_VALUE);
		if (entries.isEmpty()) {
			return null;
		}
		int min = Integer.MAX_VALUE;
		int max = -1;
		for (SourceMapEntry entry : entries) {
			int lineNum = entry.getLineNumber();
			if (lineNum < min) {
				min = lineNum;
			}
			if (lineNum > max) {
				max = lineNum;
			}
		}
		return new SourceLineBounds(min, max);
	}

	/**
	 * Normalizes paths encountered in DWARF debug info.
	 * Relative paths are made absolute with base /{@code baseDir}/.  If normalization of "/../" 
	 * subpaths results in a path "above" /{@code baseDir}/, the returned path will be based at 
	 * "baseDir_i" where i is the count of initial "/../" in the normalized path.
	 * Additionally, any backslashes are converted to forward slashes (backslashes can occur in
	 * files produced by MinGW).
	 * @param path path to normalize
	 * @param baseDir name of artificial root directory
	 * @return normalized path
	 * @throws IllegalArgumentException if the path is not valid or if baseDir contains a
	 * non-alphanumeric, non-underscore character
	 */
	public static String normalizeDwarfPath(String path, String baseDir) {
		if (StringUtils.isEmpty(baseDir)) {
			throw new IllegalArgumentException("baseDir cannot be empty");
		}
		Matcher matcher = dirNameValidator.matcher(baseDir);
		if (matcher.find()) {
			throw new IllegalArgumentException(
				"baseDir must consist of alphanumeric characters or underscores");
		}
		boolean based = false;
		if (path.startsWith("./")) {
			path = "/" + baseDir + path.substring(1);
			based = true;
		}
		path = FSUtilities.normalizeNativePath(path);
		try {
			URI uri = new URI("file", null, path, null).normalize();
			path = uri.getPath();
		}
		catch (URISyntaxException e) {
			throw new IllegalArgumentException("path not valid: " + e.getMessage());
		}
		int numDotDots = 0;
		while (path.startsWith("/..")) {
			path = path.substring(3);
			numDotDots += 1;
		}
		if (numDotDots == 0) {
			if (!based) {
				return path; // baseDir not necessary: path normalizes to absolute path without it 
			}
			if (path.startsWith("/" + baseDir)) {
				return path; // adding initial /baseDir was sufficient
			}
		}
		if (based) {
			numDotDots += 1; //initial baseDir was consumed by interior /../ during normalization
		}
		String count = numDotDots == 0 ? "" : "_" + Integer.toString(numDotDots);
		return "/" + baseDir + count + path;
	}

	/**
	 * A record containing the minimum and maximum mapped line numbers
	 * @param min minimum line number
	 * @param max maximum line number
	 */
	public static record SourceLineBounds(int min, int max) {

		public SourceLineBounds(int min, int max) {
			if (min < 0) {
				throw new IllegalArgumentException("min must be greater than or equal to 0");
			}
			if (max < 0) {
				throw new IllegalArgumentException("max must be greater than or equal to 0");
			}
			if (max < min) {
				throw new IllegalArgumentException("max must be greater than or equal to min");
			}
			this.min = min;
			this.max = max;

		}

	}

}
