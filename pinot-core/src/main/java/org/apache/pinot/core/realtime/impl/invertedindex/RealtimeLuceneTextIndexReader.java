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
package org.apache.pinot.core.realtime.impl.invertedindex;

import java.io.File;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.pinot.core.segment.creator.impl.inv.text.LuceneTextIndexCreator;
import org.apache.pinot.core.segment.index.readers.InvertedIndexReader;
import org.apache.pinot.core.segment.index.readers.text.LuceneDocIdCollector;
import org.roaringbitmap.IntIterator;
import org.roaringbitmap.buffer.MutableRoaringBitmap;
import org.slf4j.LoggerFactory;


/**
 * Lucene text index reader supporting near realtime search. An instance of this
 * is created per consuming segment by {@link org.apache.pinot.core.indexsegment.mutable.MutableSegmentImpl}.
 * Internally it uses {@link LuceneTextIndexCreator} for adding documents to the lucene index
 * as and when they are indexed by the consuming segment.
 */
public class RealtimeLuceneTextIndexReader implements InvertedIndexReader<MutableRoaringBitmap> {
  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(RealtimeLuceneTextIndexReader.class);

  private final QueryParser _queryParser;
  private final LuceneTextIndexCreator _indexCreator;
  private SearcherManager _searcherManager;
  private final String _column;
  private final String _segmentName;

  /**
   * Created by {@link org.apache.pinot.core.indexsegment.mutable.MutableSegmentImpl}
   * for each column on which text index has been enabled
   * @param column column name
   * @param segmentIndexDir realtime segment consumer dir
   * @param segmentName realtime segment name
   */
  public RealtimeLuceneTextIndexReader(String column, File segmentIndexDir, String segmentName) {
    _column = column;
    _segmentName = segmentName;
    try {
      // indexCreator.close() is necessary for cleaning up the resources associated with lucene
      // index writer that was indexing data realtime. We close the indexCreator
      // when the realtime segment is destroyed (we would have already committed the
      // segment and converted it into offline before destroy is invoked)
      // So committing the lucene index for the realtime in-memory segment is not necessary
      // as it is already part of the offline segment after the conversion.
      // This is why "commitOnClose" is set to false when creating the lucene index writer
      // for realtime
      _indexCreator =
          new LuceneTextIndexCreator(column, new File(segmentIndexDir.getAbsolutePath() + "/" + segmentName),
              false /* commitOnClose */);
      IndexWriter indexWriter = _indexCreator.getIndexWriter();
      _searcherManager = new SearcherManager(indexWriter, false, false, null);
    } catch (Exception e) {
      LOGGER.error("Failed to instantiate realtime Lucene index reader for column {}, exception {}", column,
          e.getMessage());
      throw new RuntimeException(e);
    }
    StandardAnalyzer analyzer = new StandardAnalyzer();
    _queryParser = new QueryParser(column, analyzer);
  }

  @Override
  public MutableRoaringBitmap getDocIds(int dictId) {
    // This should not be called from anywhere. If it happens, there is a bug
    // and that's why we throw illegal state exception
    throw new UnsupportedOperationException("Using dictionary ID is not supported on Lucene inverted index");
  }

  /**
   * Get docIds from the text inverted index for a given raw value
   * @param value value to look for in the inverted index
   * @return docIDs in bitmap
   */
  @Override
  public MutableRoaringBitmap getDocIds(Object value) {
    String searchQuery = (String) value;
    MutableRoaringBitmap docIDs = new MutableRoaringBitmap();
    Collector docIDCollector = new LuceneDocIdCollector(docIDs);
    IndexSearcher indexSearcher = null;
    try {
      Query query = _queryParser.parse(searchQuery);
      indexSearcher = _searcherManager.acquire();
      indexSearcher.search(query, docIDCollector);
      return getPinotDocIds(indexSearcher, docIDs);
    } catch (Exception e) {
      LOGGER
          .error("Failed while searching the realtime text index for column {}, search query {}, exception {}", _column,
              searchQuery, e.getMessage());
      throw new RuntimeException(e);
    } finally {
      try {
        if (indexSearcher != null) {
          _searcherManager.release(indexSearcher);
        }
      } catch (Exception e) {
        LOGGER.error("Failed while releasing the searcher manager for realtime text index for column {}, exception {}",
            _column, e.getMessage());
      }
    }
  }

  private MutableRoaringBitmap getPinotDocIds(IndexSearcher indexSearcher, MutableRoaringBitmap luceneDocIds) {
    IntIterator luceneDocIDIterator = luceneDocIds.getIntIterator();
    MutableRoaringBitmap actualDocIDs = new MutableRoaringBitmap();
    try {
      while (luceneDocIDIterator.hasNext()) {
        int luceneDocId = luceneDocIDIterator.next();
        Document document = indexSearcher.doc(luceneDocId);
        int pinotDocId = Integer.valueOf(document.get(LuceneTextIndexCreator.LUCENE_INDEX_DOC_ID_COLUMN_NAME));
        actualDocIDs.add(pinotDocId);
      }
    } catch (Exception e) {
      LOGGER.error("Failure while retrieving document from index for column {}, exception {}", _column, e.getMessage());
      throw new RuntimeException(e);
    }
    return actualDocIDs;
  }

  public void addDoc(Object doc, int docIdCounter) {
    _indexCreator.addDoc(doc, docIdCounter);
  }

  @Override
  public void close() {
    try {
      _searcherManager.close();
      _searcherManager = null;
      _indexCreator.close();
    } catch (Exception e) {
      LOGGER.error("Failed while closing the realtime text index for column {}, exception {}", _column, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  SearcherManager getSearcherManager() {
    return _searcherManager;
  }
}