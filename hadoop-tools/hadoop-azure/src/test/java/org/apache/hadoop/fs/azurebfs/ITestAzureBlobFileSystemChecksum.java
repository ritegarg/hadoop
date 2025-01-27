/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azurebfs;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;

import org.apache.hadoop.fs.azurebfs.contracts.services.AzureServiceErrorCode;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.mockito.Mockito;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AbfsInvalidChecksumException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AbfsRestOperationException;
import org.apache.hadoop.fs.azurebfs.contracts.services.AppendRequestParameters;
import org.apache.hadoop.fs.azurebfs.services.AbfsClient;
import org.apache.hadoop.fs.impl.OpenFileParameters;

import static org.apache.hadoop.fs.azurebfs.constants.ConfigurationKeys.FS_AZURE_BUFFERED_PREAD_DISABLE;
import static org.apache.hadoop.fs.azurebfs.constants.FileSystemConfigurations.ONE_MB;
import static org.apache.hadoop.fs.azurebfs.contracts.services.AppendRequestParameters.Mode.APPEND_MODE;
import static org.apache.hadoop.test.LambdaTestUtils.intercept;
import static org.mockito.ArgumentMatchers.any;

/**
 * Test For Verifying Checksum Related Operations
 */
public class ITestAzureBlobFileSystemChecksum extends AbstractAbfsIntegrationTest {

  private static final int MB_2 = 2 * ONE_MB;
  private static final int MB_3 = 3 * ONE_MB;
  private static final int MB_4 = 4 * ONE_MB;
  private static final int MB_8 = 8 * ONE_MB;
  private static final int MB_15 = 15 * ONE_MB;
  private static final int MB_16 = 16 * ONE_MB;
  private static final String INVALID_MD5_TEXT = "Text for Invalid MD5 Computation";

  public ITestAzureBlobFileSystemChecksum() throws Exception {
    super();
  }

  @Test
  public void testWriteReadWithChecksum() throws Exception {
    testWriteReadWithChecksumInternal(true);
    testWriteReadWithChecksumInternal(false);
  }

  @Test
  public void testAppendWithChecksumAtDifferentOffsets() throws Exception {
    AzureBlobFileSystem fs = getConfiguredFileSystem(MB_4, MB_4, true);
    AbfsClient client = fs.getAbfsStore().getClient();
    Path path = path("testPath" + getMethodName());
    try (FSDataOutputStream out = fs.create(path)) {
      byte[] data = generateRandomBytes(MB_4);

      appendWithOffsetHelper(client, path, data, fs, 0);
      appendWithOffsetHelper(client, path, data, fs, ONE_MB);
      appendWithOffsetHelper(client, path, data, fs, MB_2);
      appendWithOffsetHelper(client, path, data, fs, MB_4 - 1);
    }
  }

  @Test
  public void testReadWithChecksumAtDifferentOffsets() throws Exception {
    AzureBlobFileSystem fs = getConfiguredFileSystem(MB_4, MB_4, true);
    AbfsClient client = fs.getAbfsStore().getClient();
    Path path = path("testPath" + getMethodName());
    byte[] data = generateRandomBytes(MB_16);

    createFileWithData(path, data, fs);
    readWithOffsetAndPositionHelper(client, path, data, fs, 0, 0);
    readWithOffsetAndPositionHelper(client, path, data, fs, MB_4, 0);
    readWithOffsetAndPositionHelper(client, path, data, fs, MB_4, ONE_MB);
    readWithOffsetAndPositionHelper(client, path, data, fs, MB_8, MB_2);
    readWithOffsetAndPositionHelper(client, path, data, fs, MB_15, MB_4 - 1);
  }

  @Test
  public void testWriteReadWithChecksumAndOptions() throws Exception {
    testWriteReadWithChecksumAndOptionsInternal(true);
    testWriteReadWithChecksumAndOptionsInternal(false);
  }

  @Test
  public void testAbfsInvalidChecksumExceptionInAppend() throws Exception {
    AzureBlobFileSystem fs = getConfiguredFileSystem(MB_4, MB_4, true);
    AbfsClient spiedClient = Mockito.spy(fs.getAbfsStore().getClient());
    Path path = path("testPath" + getMethodName());
    fs.create(path);
    byte[] data= generateRandomBytes(MB_4);
    String invalidMD5Hash = spiedClient.computeMD5Hash(
            INVALID_MD5_TEXT.getBytes(), 0, INVALID_MD5_TEXT.length());
    Mockito.doReturn(invalidMD5Hash).when(spiedClient).computeMD5Hash(any(),
        any(Integer.class), any(Integer.class));
    AbfsRestOperationException ex = intercept(AbfsInvalidChecksumException.class, () -> {
      appendWithOffsetHelper(spiedClient, path, data, fs, 0);
    });

    Assertions.assertThat(ex.getErrorCode())
        .describedAs("Exception Message should contain MD5Mismatch")
        .isEqualTo(AzureServiceErrorCode.MD5_MISMATCH);
  }

  @Test
  public void testAbfsInvalidChecksumExceptionInRead() throws Exception {
    AzureBlobFileSystem fs = getConfiguredFileSystem(MB_4, MB_4, true);
    AbfsClient spiedClient = Mockito.spy(fs.getAbfsStore().getClient());
    Path path = path("testPath" + getMethodName());
    byte[] data = generateRandomBytes(MB_3);
    createFileWithData(path, data, fs);

    String invalidMD5Hash = spiedClient.computeMD5Hash(
            INVALID_MD5_TEXT.getBytes(), 0, INVALID_MD5_TEXT.length());
    Mockito.doReturn(invalidMD5Hash).when(spiedClient).computeMD5Hash(any(),
        any(Integer.class), any(Integer.class));

    intercept(AbfsInvalidChecksumException.class, () -> {
      readWithOffsetAndPositionHelper(spiedClient, path, data, fs, 0, 0);
    });
  }

  private void testWriteReadWithChecksumInternal(final boolean readAheadEnabled)
      throws Exception {
    AzureBlobFileSystem fs = getConfiguredFileSystem(MB_4, MB_4, readAheadEnabled);
    final int dataSize = MB_16 + 1000;
    Path testPath = path("testPath" + getMethodName());
    byte[] bytesUploaded = generateRandomBytes(dataSize);

    createFileWithData(testPath, bytesUploaded, fs);

    try (FSDataInputStream in = fs.open(testPath)) {
      byte[] bytesRead = new byte[bytesUploaded.length];
      in.read(bytesRead, 0, dataSize);

      // Verify that the data read is same as data written
      Assertions.assertThat(bytesRead)
              .describedAs("Bytes read with checksum enabled are not as expected")
              .containsExactly(bytesUploaded);
    }
  }

  /**
   * Verify that the checksum computed on client side matches with the one
   * computed at server side. If not, request will fail with 400 Bad request.
   * @param client
   * @param path
   * @param data
   * @param fs
   * @param offset
   * @throws Exception
   */
  private void appendWithOffsetHelper(AbfsClient client, Path path,
      byte[] data, AzureBlobFileSystem fs, final int offset) throws Exception {
    AppendRequestParameters reqParams = new AppendRequestParameters(
        0, offset, data.length - offset, APPEND_MODE, false, null, true);
    client.append(path.toUri().getPath(), data, reqParams, null, null,
        getTestTracingContext(fs, false));
  }

  /**
   * Verify that the checksum returned by server is same as computed on client
   * side even when read from different positions and stored at different offsets
   * If not server request will pass but client.read() will fail with
   * {@link org.apache.hadoop.fs.azurebfs.contracts.exceptions.AbfsInvalidChecksumException}
   * @param client
   * @param path
   * @param data
   * @param fs
   * @param position
   * @param offset
   * @throws Exception
   */
  private void readWithOffsetAndPositionHelper(AbfsClient client, Path path,
      byte[] data, AzureBlobFileSystem fs, final int position,
      final int offset) throws Exception {

    int bufferLength = fs.getAbfsStore().getAbfsConfiguration().getReadBufferSize();
    byte[] readBuffer = new byte[bufferLength];
    final int readLength = bufferLength - offset;

    client.read(path.toUri().getPath(), position, readBuffer, offset, readLength,
        "*", null, null, getTestTracingContext(fs, false));

    byte[] actual = Arrays.copyOfRange(readBuffer, offset, offset + readLength);
    byte[] expected = Arrays.copyOfRange(data, position, readLength + position);
    Assertions.assertThat(actual)
        .describedAs("Data read should be same as Data Written")
        .containsExactly(expected);
  }

  private void testWriteReadWithChecksumAndOptionsInternal(
      final boolean readAheadEnabled) throws Exception {
    AzureBlobFileSystem fs = getConfiguredFileSystem(MB_8, ONE_MB, readAheadEnabled);
    final int dataSize = MB_16 + 1000;

    Path testPath = path("testPath" + getMethodName());
    byte[] bytesUploaded = generateRandomBytes(dataSize);
    createFileWithData(testPath, bytesUploaded, fs);

    Configuration cpm1 = new Configuration();
    cpm1.setBoolean(FS_AZURE_BUFFERED_PREAD_DISABLE, true);
    try (FSDataInputStream in = fs.openFileWithOptions(testPath,
        new OpenFileParameters().withOptions(cpm1)
            .withMandatoryKeys(new HashSet<>())).get()) {
      byte[] bytesRead = new byte[dataSize];

      in.read(1, bytesRead, 1, MB_4);

      // Verify that the data read is same as data written
      Assertions.assertThat(Arrays.copyOfRange(bytesRead, 1, MB_4))
              .describedAs("Bytes read with checksum enabled are not as expected")
              .containsExactly(Arrays.copyOfRange(bytesUploaded, 1, MB_4));
    }
  }

  private void createFileWithData(Path path, byte[] data, AzureBlobFileSystem fs) throws Exception {
    try (FSDataOutputStream out = fs.create(path)) {
      out.write(data);
      out.hflush();
    }
  }

  private AzureBlobFileSystem getConfiguredFileSystem(final int writeBuffer,
      final int readBuffer, final boolean readAheadEnabled) throws Exception {
    AzureBlobFileSystem fs = createFileSystem();
    AbfsConfiguration abfsConf = fs.getAbfsStore().getAbfsConfiguration();
    abfsConf.setIsChecksumValidationEnabled(true);
    abfsConf.setWriteBufferSize(writeBuffer);
    abfsConf.setReadBufferSize(readBuffer);
    abfsConf.setReadAheadEnabled(readAheadEnabled);
    return fs;
  }

  public static byte[] generateRandomBytes(int numBytes) {
    SecureRandom secureRandom = new SecureRandom();
    byte[] randomBytes = new byte[numBytes];
    secureRandom.nextBytes(randomBytes);
    return randomBytes;
  }
}
