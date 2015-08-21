/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.origin.lib;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.api.impl.XMLChar;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.parser.DataParser;
import com.streamsets.pipeline.lib.parser.DataParserException;
import com.streamsets.pipeline.lib.parser.DataParserFactory;
import com.streamsets.pipeline.lib.parser.DataParserFactoryBuilder;
import com.streamsets.pipeline.lib.parser.avro.AvroDataParserFactory;
import com.streamsets.pipeline.lib.parser.log.LogDataFormatValidator;
import com.streamsets.pipeline.lib.parser.log.RegExConfig;
import com.streamsets.pipeline.lib.parser.xml.XmlDataParserFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataFormatParser {
  private final String parentName;
  private final DataFormatConfig dataFormatConfig;
  private final MessageConfig messageConfig;
  private LogDataFormatValidator logDataFormatValidator;
  private Charset messageCharset;
  private DataParserFactory parserFactory;

  public DataFormatParser(String parentName, DataFormatConfig dataFormatConfig, MessageConfig messageConfig) {
    this.parentName = parentName;
    this.dataFormatConfig = dataFormatConfig;
    this.messageConfig = messageConfig;
  }

  public List<Stage.ConfigIssue> init(Source.Context context) {
    List<Stage.ConfigIssue> issues = new ArrayList<>();
    switch (dataFormatConfig.dataFormat) {
      case JSON:
        if (dataFormatConfig.jsonMaxObjectLen < 1) {
          issues.add(context.createConfigIssue(DataFormat.JSON.name(), "maxJsonObjectLen", ParserErrors.PARSER_04));
        }
        break;
      case TEXT:
        if (dataFormatConfig.textMaxLineLen < 1) {
          issues.add(context.createConfigIssue(DataFormat.TEXT.name(), "maxLogLineLength", ParserErrors.PARSER_04));
        }
        break;
      case DELIMITED:
        if (dataFormatConfig.csvMaxObjectLen < 1) {
          issues.add(context.createConfigIssue(DataFormat.DELIMITED.name(), "csvMaxObjectLen", ParserErrors.PARSER_04));
        }
        break;
      case XML:
        if (messageConfig != null && messageConfig.produceSingleRecordPerMessage) {
          issues.add(context.createConfigIssue(parentName, "produceSingleRecordPerMessage",
            ParserErrors.PARSER_06));
        }
        if (dataFormatConfig.xmlMaxObjectLen < 1) {
          issues.add(context.createConfigIssue(DataFormat.XML.name(), "maxXmlObjectLen", ParserErrors.PARSER_04));
        }
        if (dataFormatConfig.xmlRecordElement != null && !dataFormatConfig.xmlRecordElement.isEmpty() &&
          !XMLChar.isValidName(dataFormatConfig.xmlRecordElement)) {
          issues.add(context.createConfigIssue(DataFormat.XML.name(), "xmlRecordElement", ParserErrors.PARSER_02,
            dataFormatConfig.xmlRecordElement));
        }
        break;
      case SDC_JSON:
        break;
      case LOG:
        logDataFormatValidator = new LogDataFormatValidator(dataFormatConfig.logMode, dataFormatConfig.logMaxObjectLen,
          dataFormatConfig.retainOriginalLine, dataFormatConfig.customLogFormat, dataFormatConfig.regex,
          dataFormatConfig.grokPatternDefinition, dataFormatConfig.grokPattern,
          dataFormatConfig.enableLog4jCustomLogFormat, dataFormatConfig.log4jCustomLogFormat, dataFormatConfig.onParseError,
          dataFormatConfig.maxStackTraceLines, DataFormat.LOG.name(),
          getFieldPathToGroupMap(dataFormatConfig.fieldPathsToGroupName));
        logDataFormatValidator.validateLogFormatConfig(issues, context);
        break;
      case AVRO:
        if(!dataFormatConfig.schemaInMessage && (dataFormatConfig.avroSchema == null || dataFormatConfig.avroSchema.isEmpty())) {
          issues.add(context.createConfigIssue(DataFormat.AVRO.name(), "avroSchema", ParserErrors.PARSER_07,
            dataFormatConfig.avroSchema));
        }
        break;
      default:
        issues.add(context.createConfigIssue(parentName, "dataFormat", ParserErrors.PARSER_05, dataFormatConfig.dataFormat));
    }

    DataParserFactoryBuilder builder = new DataParserFactoryBuilder(context, dataFormatConfig.dataFormat.getParserFormat())
      .setCharset(Charset.defaultCharset());
    if (dataFormatConfig.charset == null) {
      messageCharset = StandardCharsets.UTF_8;
    } else {
      try {
        messageCharset = Charset.forName(dataFormatConfig.charset);
      } catch (UnsupportedCharsetException ex) {
        // setting it to a valid one so the parser factory can be configured and tested for more errors
        messageCharset = StandardCharsets.UTF_8;
        issues.add(context.createConfigIssue(parentName, "charset", ParserErrors.PARSER_01, dataFormatConfig.charset));
      }
    }
    builder.setCharset(messageCharset).setRemoveCtrlChars(dataFormatConfig.removeCtrlChars);

    switch (dataFormatConfig.dataFormat) {
      case TEXT:
        builder.setMaxDataLen(dataFormatConfig.textMaxLineLen);
        break;
      case JSON:
        builder.setMode(dataFormatConfig.jsonContent);
        builder.setMaxDataLen(dataFormatConfig.jsonMaxObjectLen);
        break;
      case DELIMITED:
        builder.setMaxDataLen(dataFormatConfig.csvMaxObjectLen);
        builder.setMode(dataFormatConfig.csvFileFormat).setMode(dataFormatConfig.csvHeader);
        break;
      case XML:
        builder.setMaxDataLen(dataFormatConfig.xmlMaxObjectLen);
        builder.setConfig(XmlDataParserFactory.RECORD_ELEMENT_KEY, dataFormatConfig.xmlRecordElement);
        break;
      case SDC_JSON:
        builder.setMaxDataLen(-1);
        break;
      case LOG:
        logDataFormatValidator.populateBuilder(builder);
        break;
      case AVRO:
        builder.setMaxDataLen(Integer.MAX_VALUE).setConfig(AvroDataParserFactory.SCHEMA_KEY, dataFormatConfig.avroSchema)
          .setConfig(AvroDataParserFactory.SCHEMA_IN_MESSAGE_KEY, dataFormatConfig.schemaInMessage);
        break;
      default:
        throw new IllegalStateException("Unknown data format: " + dataFormatConfig.dataFormat);
    }
    parserFactory = builder.build();
    return issues;
  }

  public List<Record> parse(Source.Context context, String messageId, byte[] payload) throws StageException {
    List<Record> records = new ArrayList<>();
    try (DataParser parser = parserFactory.getParser(messageId, payload)) {
      Record record = parser.parse();
      while (record != null) {
        records.add(record);
        record = parser.parse();
      }
    } catch (IOException |DataParserException ex) {
      handleException(context, messageId, ex);
    }
    if (messageConfig.produceSingleRecordPerMessage) {
      List<Field> list = new ArrayList<>();
      for (Record record : records) {
        list.add(record.get());
      }
      Record record = records.get(0);
      record.set(Field.create(list));
      records.clear();
      records.add(record);
    }
    return records;
  }

  private void handleException(Source.Context context, String messageId, Exception ex) throws StageException {
    switch (context.getOnErrorRecord()) {
      case DISCARD:
        break;
      case TO_ERROR:
        context.reportError(ParserErrors.PARSER_03, messageId, ex.toString(), ex);
        break;
      case STOP_PIPELINE:
        if (ex instanceof StageException) {
          throw (StageException) ex;
        } else {
          throw new StageException(ParserErrors.PARSER_03, messageId, ex.toString(), ex);
        }
      default:
        throw new IllegalStateException(Utils.format("Unknown on error value '{}'",
          context.getOnErrorRecord(), ex));
    }
  }

  public Charset getCharset() {
    return messageCharset;
  }

  private Map<String, Integer> getFieldPathToGroupMap(List<RegExConfig> fieldPathsToGroupName) {
    if(fieldPathsToGroupName == null) {
      return new HashMap<>();
    }
    Map<String, Integer> fieldPathToGroup = new HashMap<>();
    for(RegExConfig r : fieldPathsToGroupName) {
      fieldPathToGroup.put(r.fieldPath, r.group);
    }
    return fieldPathToGroup;
  }

}
