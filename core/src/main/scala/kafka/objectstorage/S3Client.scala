/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.objectstorage

import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

import java.nio.file.Files

class KafkaS3Client(s3Client: S3Client) {
  def upload(): Unit = {
    val filePath = Files.createTempFile("upload-", ".txt")
    Files.write(filePath, "foo".getBytes)

    val putObjectRequest = PutObjectRequest.builder()
      .bucket("dd-kafka-tiered-storage-staging-us1-staging-dog")
      .key("hackweek/kafka-martin-tst3-094d/test")
      .build()

    s3Client.putObject(putObjectRequest, filePath)
  }
}

object KafkaS3Client {
  def apply(): KafkaS3Client = {
    val s3ClientBuilder = S3Client.builder()
    s3ClientBuilder.region(Region.US_EAST_1)
    s3ClientBuilder.credentialsProvider(InstanceProfileCredentialsProvider)
    new KafkaS3Client(s3ClientBuilder.build())
  }
}