/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.lens.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.List;

import javax.ws.rs.client.*;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.xml.XMLConstants;
import javax.xml.bind.*;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.lens.api.APIResult;
import org.apache.lens.api.APIResult.Status;
import org.apache.lens.api.DateTime;
import org.apache.lens.api.StringList;
import org.apache.lens.api.metastore.*;

import org.glassfish.jersey.media.multipart.*;
import org.xml.sax.SAXException;

import com.google.common.base.Joiner;
import com.google.common.io.Files;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LensMetadataClient {

  private final LensConnection connection;
  private final LensConnectionParams params;
  private final ObjectFactory objFact;

  public static final Unmarshaller JAXB_UNMARSHALLER;

  static {
    try {
      ClassLoader classLoader = LensMetadataClient.class.getClassLoader();
      SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      URI uri = classLoader.getResource("cube-0.1.xsd").toURI();
      log.info("URI for cube schema: {}", uri.toString());
      Schema schema = sf.newSchema(uri.toURL());
      JAXBContext jaxbContext = JAXBContext.newInstance(ObjectFactory.class);
      JAXB_UNMARSHALLER = jaxbContext.createUnmarshaller();
      JAXB_UNMARSHALLER.setSchema(schema);
    } catch (JAXBException | URISyntaxException | MalformedURLException | SAXException e) {
      log.error("Could not initialize JAXBContext. ", e);
      throw new RuntimeException("Could not initialize JAXBContext. ", e);
    }
  }


  public LensMetadataClient(LensConnection connection) {
    this.connection = connection;
    this.params = connection.getLensConnectionParams();
    objFact = new ObjectFactory();

  }

  private WebTarget getMetastoreWebTarget(Client client) {
    return client.target(params.getBaseConnectionUrl()).path(
      params.getMetastoreResourcePath());
  }


  private WebTarget getMetastoreWebTarget() {
    Client client = ClientBuilder.newBuilder().register(MultiPartFeature.class).build();
    return getMetastoreWebTarget(client);
  }

  public List<String> getAlldatabases() {
    WebTarget target = getMetastoreWebTarget();
    StringList databases = target.path("databases")
      .queryParam("sessionid", connection.getSessionHandle())
      .request().get(StringList.class);
    return databases.getElements();
  }

  public String getCurrentDatabase() {
    WebTarget target = getMetastoreWebTarget();
    String database = target.path("databases").path("current")
      .queryParam("sessionid", connection.getSessionHandle())
      .request().get(String.class);
    return database;
  }


  public APIResult setDatabase(String database) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("databases").path("current")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML_TYPE)
      .put(Entity.xml(database), APIResult.class);
    return result;
  }

  public APIResult createDatabase(String database, boolean ignoreIfExists) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("databases")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("ignoreIfExisting", ignoreIfExists)
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(database), APIResult.class);
    return result;
  }

  public APIResult createDatabase(String database) {
    return createDatabase(database, false);
  }

  public APIResult dropDatabase(String database, boolean cascade) {
    WebTarget target = getMetastoreWebTarget();
    return target.path("databases").path(database)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("cascade", cascade)
      .request().delete(APIResult.class);
  }

  public APIResult dropDatabase(String database) {
    return dropDatabase(database, false);
  }

  public List<String> getAllNativeTables() {
    WebTarget target = getMetastoreWebTarget();
    StringList nativetables = target.path("nativetables")
      .queryParam("sessionid", connection.getSessionHandle())
      .request().get(StringList.class);
    return nativetables.getElements();
  }

  public XNativeTable getNativeTable(String tblName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XNativeTable> htable = target.path("nativetables").path(tblName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).get(new GenericType<JAXBElement<XNativeTable>>() {
      });
    return htable.getValue();
  }

  public List<String> getAllCubes() {
    WebTarget target = getMetastoreWebTarget();
    StringList cubes = target.path("cubes")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).get(StringList.class);
    return cubes.getElements();
  }

  public APIResult dropAllCubes() {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("cubes")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).delete(APIResult.class);
    return result;
  }

  public APIResult createCube(XCube cube) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("cubes")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXCube(cube)), APIResult.class);
    return result;
  }

  private <T> T readFromXML(String filename) throws JAXBException, IOException {
    if (filename.startsWith("/")) {
      return ((JAXBElement<T>) JAXB_UNMARSHALLER.unmarshal(new File(filename))).getValue();
    } else {
      // load from classpath
      InputStream file = Thread.currentThread().getContextClassLoader().getResourceAsStream(filename);
      if (file == null) {
        throw new IOException("File not found:" + filename);
      }
      return ((JAXBElement<T>) JAXB_UNMARSHALLER.unmarshal(file)).getValue();
    }
  }

  public APIResult createCube(String cubeSpec) {
    try {
      return createCube(this.<XCube>readFromXML(cubeSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult updateCube(String cubeName, XCube cube) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("cubes").path(cubeName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXCube(cube)), APIResult.class);
    return result;
  }

  public APIResult updateCube(String cubeName, String cubeSpec) {
    try {
      return updateCube(cubeName, this.<XCube>readFromXML(cubeSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public XCube getCube(String cubeName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XCube> cube = target.path("cubes").path(cubeName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).get(new GenericType<JAXBElement<XCube>>() {
      });
    return cube.getValue();
  }

  public XFlattenedColumns getQueryableFields(String tableName, boolean flattened) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XFlattenedColumns> fields = target.path("flattened").path(tableName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("add_chains", flattened)
      .request(MediaType.APPLICATION_XML).get(new GenericType<JAXBElement<XFlattenedColumns>>() {
      });
    return fields.getValue();
  }

  public XJoinChains getJoinChains(String tableName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XJoinChains> fields = target.path("chains").path(tableName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).get(new GenericType<JAXBElement<XJoinChains>>() {
      });
    return fields.getValue();
  }

  public APIResult dropCube(String cubeName) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("cubes").path(cubeName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).delete(APIResult.class);
    return result;
  }

  public List<String> getAllDimensions() {
    WebTarget target = getMetastoreWebTarget();
    StringList dimensions = target.path("dimensions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).get(StringList.class);
    return dimensions.getElements();
  }

  public APIResult dropAllDimensions() {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimensions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).delete(APIResult.class);
    return result;
  }

  public APIResult createDimension(XDimension dimension) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimensions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXDimension(dimension)), APIResult.class);
    return result;
  }

  public APIResult createDimension(String dimSpec) {
    try {
      return createDimension(this.<XDimension>readFromXML(dimSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult updateDimension(String dimName, XDimension dimension) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimensions").path(dimName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXDimension(dimension)), APIResult.class);
    return result;
  }

  public APIResult updateDimension(String dimName, String dimSpec) {
    try {
      return updateDimension(dimName, this.<XDimension>readFromXML(dimSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public XDimension getDimension(String dimName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XDimension> dim = target.path("dimensions").path(dimName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).get(new GenericType<JAXBElement<XDimension>>() {
      });
    return dim.getValue();
  }

  public APIResult dropDimension(String dimName) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimensions").path(dimName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML).delete(APIResult.class);
    return result;
  }

  public List<String> getAllStorages() {
    WebTarget target = getMetastoreWebTarget();
    StringList storages = target.path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request().get(StringList.class);
    return storages.getElements();
  }


  public APIResult createNewStorage(XStorage storage) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXStorage(storage)), APIResult.class);
    return result;
  }


  public APIResult createNewStorage(String storage) {
    try {
      return createNewStorage(this.<XStorage>readFromXML(storage));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult dropAllStorages() {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult updateStorage(String storageName, XStorage storage) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("storages").path(storageName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXStorage(storage)), APIResult.class);
    return result;
  }

  public APIResult updateStorage(String storageName, String storage) {
    try {
      return updateStorage(storageName, this.<XStorage>readFromXML(storage));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public XStorage getStorage(String storageName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XStorage> result = target.path("storages").path(storageName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XStorage>>() {
      });
    return result.getValue();
  }

  public APIResult dropStorage(String storageName) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("storages").path(storageName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public List<String> getAllFactTables(String cubeName) {
    if (cubeName == null) {
      return getAllFactTables();
    }
    WebTarget target = getMetastoreWebTarget();
    StringList factTables;
    factTables = target.path("cubes").path(cubeName).path("facts")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class);
    return factTables.getElements();
  }

  public List<String> getAllFactTables() {
    WebTarget target = getMetastoreWebTarget();
    StringList factTables;
    factTables = target.path("facts")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class);

    return factTables.getElements();
  }


  public APIResult deleteAllFactTables(boolean cascade) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("cascade", cascade)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }


  public XFactTable getFactTable(String factTableName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XFactTable> table = target.path("facts").path(factTableName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XFactTable>>() {
      });
    return table.getValue();
  }

  public APIResult createFactTable(XFactTable f) {
    WebTarget target = getMetastoreWebTarget();
    FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid")
      .build(), this.connection.getSessionHandle(), MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(
      FormDataContentDisposition.name("fact").fileName("fact").build(),
      objFact.createXFactTable(f), MediaType.APPLICATION_XML_TYPE));
    APIResult result = target.path("facts")
      .request(MediaType.APPLICATION_XML_TYPE)
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE),
        APIResult.class);
    return result;
  }

  public APIResult createFactTable(String factSpec) {
    try {
      return createFactTable(this.<XFactTable>readFromXML(factSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  private String getContent(String path) {
    try {
      List<String> content = null;
      if (path.startsWith("/")) {
        // path provided is absolute path
        content = Files.readLines(new File(path), Charset.defaultCharset());
      } else {
        // load from classpath
        URI uri = Thread.currentThread().getContextClassLoader().getResource(path).toURI();
        content = Files.readLines(new File(uri),
          Charset.defaultCharset());
      }
      return Joiner.on("\n").skipNulls().join(content);
    } catch (IOException e) {
      log.error("IO Error reading content ", e);
      throw new IllegalStateException(e);
    } catch (URISyntaxException e) {
      log.error("URI Error reading content ", e);
      throw new IllegalStateException(e);
    }
  }


  public APIResult updateFactTable(String factName, XFactTable table) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML_TYPE)
      .put(Entity.xml(objFact.createXFactTable(table)), APIResult.class);
    return result;
  }

  public APIResult updateFactTable(String factName, String table) {
    try {
      return updateFactTable(factName, this.<XFactTable>readFromXML(table));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult dropFactTable(String factName, boolean cascade) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("cascade", cascade)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult dropFactTable(String factName) {
    return dropFactTable(factName, false);
  }

  public List<String> getAllStoragesOfFactTable(String factName) {
    WebTarget target = getMetastoreWebTarget();
    StringList storageList = target.path("facts").path(factName).path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class);
    return storageList.getElements();
  }

  public APIResult dropAllStoragesOfFactTable(String factName) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factName).path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult addStorageToFactTable(String factname, XStorageTableElement storage) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factname).path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXStorageTableElement(storage)), APIResult.class);
    return result;
  }

  public APIResult addStorageToFactTable(String factname, String storageSpec) {
    try {
      return addStorageToFactTable(factname, this.<XStorageTableElement>readFromXML(storageSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult dropStorageFromFactTable(String factName, String storageName) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factName).path("storages").path(storageName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public XStorageTableElement getStorageOfFactTable(String factName, String storageName) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XStorageTableElement> element = target.path("facts")
      .path(factName).path("storages").path(storageName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XStorageTableElement>>() {
      });
    return element.getValue();
  }

  public List<XPartition> getPartitionsOfFactTable(String factName, String storage, String filter) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XPartitionList> elements = target.path("facts").path(factName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("filter", filter)
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XPartitionList>>() {
      });
    return elements.getValue().getPartition();
  }

  public List<XPartition> getPartitionsOfFactTable(String factName, String storage) {
    return getPartitionsOfFactTable(factName, storage, "");
  }

  public APIResult dropPartitionsOfFactTable(String factName, String storage, String filter) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("filter", filter)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult dropPartitionsOfFactTable(String factName, String storage) {
    return dropPartitionsOfFactTable(factName, storage, "");
  }

  public APIResult dropPartitionsOfFactTable(String factName, String storage,
    List<String> partitions) {
    String values = Joiner.on(",").skipNulls().join(partitions);

    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(factName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("values", values)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }


  public List<String> getAllDimensionTables(String dimensionName) {
    if (dimensionName == null) {
      return getAllDimensionTables();
    }
    WebTarget target = getMetastoreWebTarget();
    StringList dimtables;
    dimtables = target.path("dimensions").path(dimensionName).path("dimtables")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class);
    return dimtables.getElements();
  }

  public List<String> getAllDimensionTables() {
    WebTarget target = getMetastoreWebTarget();
    StringList dimtables;
    dimtables = target.path("dimtables")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class);
    return dimtables.getElements();
  }

  public APIResult createDimensionTable(XDimensionTable table) {
    WebTarget target = getMetastoreWebTarget();

    FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
      this.connection.getSessionHandle(), MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(
      FormDataContentDisposition.name("dimensionTable").fileName("dimtable").build(),
      objFact.createXDimensionTable(table), MediaType.APPLICATION_XML_TYPE));

    APIResult result = target.path("dimtables")
      .request(MediaType.APPLICATION_XML)
      .post(Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), APIResult.class);
    return result;
  }

  public APIResult createDimensionTable(String tableXml) {
    try {
      return createDimensionTable(this.<XDimensionTable>readFromXML(tableXml));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }


  public APIResult updateDimensionTable(XDimensionTable table) {
    String dimTableName = table.getTableName();
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTableName)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXDimensionTable(table)), APIResult.class);
    return result;
  }

  public APIResult updateDimensionTable(String dimTblName, String dimSpec) {
    try {
      XDimensionTable dimensionTable = readFromXML(dimSpec);
      dimensionTable.setTableName(dimTblName);
      return updateDimensionTable(dimensionTable);
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }

  }

  public APIResult dropDimensionTable(String table, boolean cascade) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(table)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("cascade", cascade)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult dropDimensionTable(String table) {
    return dropDimensionTable(table, false);
  }

  public XDimensionTable getDimensionTable(String table) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XDimensionTable> result = target.path("dimtables").path(table)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XDimensionTable>>() {
      });
    return result.getValue();
  }


  public List<String> getAllStoragesOfDimTable(String dimTblName) {
    WebTarget target = getMetastoreWebTarget();
    StringList list = target.path("dimtables").path(dimTblName).path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class);
    return list.getElements();
  }

  public APIResult addStorageToDimTable(String dimTblName, XStorageTableElement table) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName).path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXStorageTableElement(table)), APIResult.class);
    return result;
  }

  public APIResult addStorageToDimTable(String dimTblName, String table) {
    try {
      return addStorageToDimTable(dimTblName, this.<XStorageTableElement>readFromXML(table));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public XStorageTableElement getStorageOfDimensionTable(String dimTblName, String storage) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XStorageTableElement> result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XStorageTableElement>>() {
      });
    return result.getValue();
  }

  public APIResult dropAllStoragesOfDimension(String dimTblName) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName).path("storages")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }


  public APIResult dropStoragesOfDimensionTable(String dimTblName, String storage) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public List<XPartition> getAllPartitionsOfDimensionTable(String dimTblName, String storage,
    String filter) {
    WebTarget target = getMetastoreWebTarget();
    JAXBElement<XPartitionList> partList = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("filter", filter)
      .request(MediaType.APPLICATION_XML)
      .get(new GenericType<JAXBElement<XPartitionList>>() {
      });
    return partList.getValue().getPartition();
  }

  public List<XPartition> getAllPartitionsOfDimensionTable(String dimTblName, String storage) {
    return getAllPartitionsOfDimensionTable(dimTblName, storage, "");
  }

  public APIResult dropAllPartitionsOfDimensionTable(String dimTblName, String storage,
    String filter) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("filter", filter)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult dropAllPartitionsOfDimensionTable(String dimTblName, String storage) {
    return dropAllPartitionsOfDimensionTable(dimTblName, storage, "");
  }

  public APIResult dropAllPartitionsOfDimensionTable(String dimTblName, String storage,
    List<String> vals) {
    String values = Joiner.on(",").skipNulls().join(vals);
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .queryParam("values", values)
      .request(MediaType.APPLICATION_XML)
      .delete(APIResult.class);
    return result;
  }

  public APIResult addPartitionToDimensionTable(String dimTblName, String storage,
    XPartition partition) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partition")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXPartition(partition)), APIResult.class);
    return result;
  }

  public APIResult addPartitionToDimensionTable(String dimTblName, String storage,
    String partitionSpec) {
    try {
      return addPartitionToDimensionTable(dimTblName, storage, (XPartition) readFromXML(partitionSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult addPartitionsToDimensionTable(String dimTblName, String storage,
    XPartitionList partitions) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXPartitionList(partitions)), APIResult.class);
    return result;
  }

  public APIResult addPartitionsToDimensionTable(String dimTblName, String storage,
    String partitionsSpec) {
    try {
      return addPartitionsToDimensionTable(dimTblName, storage, (XPartitionList) readFromXML(partitionsSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult addPartitionToFactTable(String fact, String storage,
    XPartition partition) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(fact)
      .path("storages").path(storage).path("partition")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXPartition(partition)), APIResult.class);
    return result;
  }

  public APIResult addPartitionToFactTable(String fact, String storage,
    String partitionSpec) {
    try {
      return addPartitionToFactTable(fact, storage, (XPartition) readFromXML(partitionSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult addPartitionsToFactTable(String fact, String storage,
    XPartitionList partitions) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(fact)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .post(Entity.xml(objFact.createXPartitionList(partitions)), APIResult.class);
    return result;
  }

  public APIResult addPartitionsToFactTable(String fact, String storage,
    String partitionsSpec) {
    try {
      return addPartitionsToFactTable(fact, storage, (XPartitionList) readFromXML(partitionsSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }
  public APIResult updatePartitionOfDimensionTable(String dimTblName, String storage,
    XPartition partition) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partition")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXPartition(partition)), APIResult.class);
    return result;
  }

  public APIResult updatePartitionOfDimensionTable(String dimTblName, String storage,
    String partitionSpec) {
    try {
      return updatePartitionOfDimensionTable(dimTblName, storage, (XPartition) readFromXML(partitionSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult updatePartitionsOfDimensionTable(String dimTblName, String storage,
    XPartitionList partitions) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("dimtables").path(dimTblName)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXPartitionList(partitions)), APIResult.class);
    return result;
  }

  public APIResult updatePartitionsOfDimensionTable(String dimTblName, String storage,
    String partitionsSpec) {
    try {
      return updatePartitionsOfDimensionTable(dimTblName, storage, (XPartitionList) readFromXML(partitionsSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult updatePartitionOfFactTable(String fact, String storage,
    XPartition partition) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(fact)
      .path("storages").path(storage).path("partition")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXPartition(partition)), APIResult.class);
    return result;
  }

  public APIResult updatePartitionOfFactTable(String fact, String storage,
    String partitionSpec) {
    try {
      return updatePartitionOfFactTable(fact, storage, (XPartition) readFromXML(partitionSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public APIResult updatePartitionsOfFactTable(String fact, String storage,
    XPartitionList partitions) {
    WebTarget target = getMetastoreWebTarget();
    APIResult result = target.path("facts").path(fact)
      .path("storages").path(storage).path("partitions")
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .put(Entity.xml(objFact.createXPartitionList(partitions)), APIResult.class);
    return result;
  }

  public APIResult updatePartitionsOfFactTable(String fact, String storage,
    String partitionsSpec) {
    try {
      return updatePartitionsOfFactTable(fact, storage, (XPartitionList) readFromXML(partitionsSpec));
    } catch (JAXBException e) {
      log.error("Unmarshalling error:", e);
      return new APIResult(Status.FAILED, "Unmarshalling failed");
    } catch (IOException e) {
      log.error("File error:", e);
      return new APIResult(Status.FAILED, "File not found");
    }
  }

  public Date getLatestDateOfCube(String cubeName, String timePartition) {
    return getMetastoreWebTarget().path("cubes").path(cubeName).path("latestdate")
      .queryParam("timeDimension", timePartition)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(DateTime.class).getDate();
  }

  public List<String> getPartitionTimelines(String factName, String storageName, String updatePeriod,
    String timeDimension) {
    return getMetastoreWebTarget().path("facts").path(factName).path("timelines")
      .queryParam("storage", storageName)
      .queryParam("updatePeriod", updatePeriod)
      .queryParam("timeDimension", timeDimension)
      .queryParam("sessionid", this.connection.getSessionHandle())
      .request(MediaType.APPLICATION_XML)
      .get(StringList.class).getElements();
  }
}
