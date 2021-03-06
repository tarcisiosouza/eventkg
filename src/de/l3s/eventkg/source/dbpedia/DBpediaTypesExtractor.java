package de.l3s.eventkg.source.dbpedia;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.l3s.eventkg.integration.DataSets;
import de.l3s.eventkg.integration.DataStoreWriter;
import de.l3s.eventkg.integration.EventKGIdMappingLoader;
import de.l3s.eventkg.integration.model.relation.DataSet;
import de.l3s.eventkg.integration.model.relation.prefix.Prefix;
import de.l3s.eventkg.integration.model.relation.prefix.PrefixEnum;
import de.l3s.eventkg.integration.model.relation.prefix.PrefixList;
import de.l3s.eventkg.meta.Language;
import de.l3s.eventkg.meta.Source;
import de.l3s.eventkg.pipeline.Config;
import de.l3s.eventkg.pipeline.Extractor;
import de.l3s.eventkg.util.FileLoader;
import de.l3s.eventkg.util.FileName;

public class DBpediaTypesExtractor extends Extractor {

	private EventKGIdMappingLoader eventKGIdMapping;

	private Map<String, Set<String>> typesPerEntity = new HashMap<String, Set<String>>();
	private Map<String, Set<String>> parentClasses = new HashMap<String, Set<String>>();

	private Map<String, Set<String>> wikidataToDBO = new HashMap<String, Set<String>>();

	public DBpediaTypesExtractor(List<Language> languages, EventKGIdMappingLoader eventKGIdMapping) {
		super("DBpediaTypesExtractor", Source.DBPEDIA, "Loads all DBpedia:ontology types.", languages);
		this.eventKGIdMapping = eventKGIdMapping;
	}

	@Override
	public void run() {

		PrefixList prefixList = PrefixList.getInstance();

		PrintWriter writer = null;
		PrintWriter writerPreview = null;
		PrintWriter writerOntology = null;
		PrintWriter writerOntologyPreview = null;

		try {
			writer = FileLoader.getWriter(FileName.ALL_TTL_TYPES_DBPEDIA);
			writerPreview = FileLoader.getWriter(FileName.ALL_TTL_TYPES_DBPEDIA_PREVIEW);
			writerOntology = FileLoader.getWriter(FileName.ALL_TTL_DBPEDIA_ONTOLOGY);
			writerOntologyPreview = FileLoader.getWriter(FileName.ALL_TTL_DBPEDIA_ONTOLOGY_PREVIEW);

			List<Prefix> prefixes = new ArrayList<Prefix>();
			prefixes.add(prefixList.getPrefix(PrefixEnum.RDF));
			prefixes.add(prefixList.getPrefix(PrefixEnum.RDFS));
			prefixes.add(prefixList.getPrefix(PrefixEnum.OWL));
			prefixes.add(prefixList.getPrefix(PrefixEnum.DBPEDIA_ONTOLOGY));
			for (String line : DataStoreWriter.createIntro(prefixes, prefixList)) {
				writer.write(line + Config.NL);
				writerPreview.write(line + Config.NL);
				writerOntology.write(line + Config.NL);
				writerOntologyPreview.write(line + Config.NL);
			}

			writeSubclasses(writerOntology, writerOntologyPreview, prefixList);

			for (Language language : languages) {
				Set<String> usedLines = new HashSet<String>();
				int lineNo = 0;
				BufferedReader br = null;
				DataSet dataSet = DataSets.getInstance().getDataSet(language, Source.DBPEDIA);

				try {
					br = FileLoader.getReader(FileName.DBPEDIA_TYPES, language);
					String line;
					while ((line = br.readLine()) != null) {
						if (line.startsWith("#"))
							continue;

						String[] parts = line.split(" ");

						String type = parts[2];

						if (!type.startsWith("<http://dbpedia.org/ontology"))
							continue;
						type = type.substring(type.lastIndexOf("/") + 1, type.length() - 1);

						// manually solve bug in Russian DBpedia (many "book"
						// types)
						if (language == Language.RU && type.equals("Book"))
							continue;

						String resource = parts[0];
						resource = resource.substring(resource.lastIndexOf("/") + 1, resource.length() - 1);
						if (resource.contains("__"))
							resource = resource.substring(0, resource.lastIndexOf("__"));

						String eventKGId = eventKGIdMapping.getEventKGId(dataSet, resource);
						if (eventKGId == null) {
							continue;
						}
						lineNo += 1;

						String lineId = resource + " " + type;

						if (usedLines.contains(lineId))
							continue;
						usedLines.add(lineId);

						if (!typesPerEntity.containsKey(eventKGId))
							typesPerEntity.put(eventKGId, new HashSet<String>());

						typesPerEntity.get(eventKGId).add(type);

						DataStoreWriter.writeTriple(writer, writerPreview, lineNo, eventKGId,
								prefixList.getPrefix(PrefixEnum.RDF).getAbbr() + "type",
								prefixList.getPrefix(PrefixEnum.DBPEDIA_ONTOLOGY).getAbbr() + type, false, dataSet);
					}
				} catch (FileNotFoundException e1) {
					e1.printStackTrace();
				} finally {
					br.close();
				}

			}

		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			writer.close();
			writerPreview.close();
			writerOntology.close();
			writerOntologyPreview.close();
		}

	}

	public Set<String> resolveTransitively(Set<String> types) {
		Set<String> typesToRemove = new HashSet<String>();

		for (String type : types) {
			if (this.parentClasses.containsKey(type)) {
				typesToRemove.addAll(this.parentClasses.get(type));
			}
		}

		types.removeAll(typesToRemove);

		return types;
	}

	private void writeSubclasses(PrintWriter writer, PrintWriter writerPreview, PrefixList prefixList) {
		int lineNo = 0;
		BufferedReader br = null;

		try {
			br = FileLoader.getReader(FileName.DBPEDIA_ONTOLOGY);
			String line;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("#"))
					continue;

				String[] parts = line.split(" ");

				if (parts[1].equals("<http://www.w3.org/2002/07/owl#equivalentClass>")) {

					String type1 = parts[0];
					if (!type1.startsWith("<http://dbpedia.org/ontology"))
						continue;
					type1 = type1.substring(type1.lastIndexOf("/") + 1, type1.length() - 1);

					String wikidataType = parts[2];
					if (!wikidataType.startsWith("<http://www.wikidata.org/entity/Q"))
						continue;
					wikidataType = wikidataType.substring(wikidataType.lastIndexOf("/") + 1, wikidataType.length() - 1);

					if (!wikidataToDBO.containsKey(wikidataType))
						wikidataToDBO.put(wikidataType, new HashSet<String>());
					wikidataToDBO.get(wikidataType).add(type1);
				}

				else if (parts[1].equals("<http://www.w3.org/2000/01/rdf-schema#subClassOf>")) {

					String type1 = parts[0];
					if (!type1.startsWith("<http://dbpedia.org/ontology"))
						continue;
					type1 = type1.substring(type1.lastIndexOf("/") + 1, type1.length() - 1);

					String type2 = parts[2];
					if (!type2.startsWith("<http://dbpedia.org/ontology"))
						continue;
					type2 = type2.substring(type2.lastIndexOf("/") + 1, type2.length() - 1);

					lineNo += 1;

					if (!parentClasses.containsKey(type1))
						parentClasses.put(type1, new HashSet<String>());
					parentClasses.get(type1).add(type2);

					DataStoreWriter.writeTriple(writer, writerPreview, lineNo,
							prefixList.getPrefix(PrefixEnum.DBPEDIA_ONTOLOGY).getAbbr() + type1,
							prefixList.getPrefix(PrefixEnum.RDFS).getAbbr() + "subClassOf",
							prefixList.getPrefix(PrefixEnum.DBPEDIA_ONTOLOGY).getAbbr() + type2, false,
							DataSets.getInstance().getDataSetWithoutLanguage(Source.DBPEDIA));
				} else if (parts[1].equals("<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>")
						&& parts[2].equals("<http://www.w3.org/2002/07/owl#Class>")) {
					String type1 = parts[0];
					if (!type1.startsWith("<http://dbpedia.org/ontology"))
						continue;
					type1 = type1.substring(type1.lastIndexOf("/") + 1, type1.length() - 1);

					lineNo += 1;

					DataStoreWriter.writeTriple(writer, writerPreview, lineNo,
							prefixList.getPrefix(PrefixEnum.DBPEDIA_ONTOLOGY).getAbbr() + type1,
							prefixList.getPrefix(PrefixEnum.RDF).getAbbr() + "type",
							prefixList.getPrefix(PrefixEnum.OWL).getAbbr() + "Class", false,
							DataSets.getInstance().getDataSetWithoutLanguage(Source.DBPEDIA));
				}
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		} finally {
			try {
				br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		createTransitiveMap();
	}

	private void createTransitiveMap() {

		System.out.println("Create transitive type map.");

		boolean changed = true;
		while (changed) {
			changed = false;
			Map<String, Set<String>> changeSet = new HashMap<String, Set<String>>();
			for (String child : parentClasses.keySet()) {
				for (String parent : parentClasses.get(child)) {

					if (parentClasses.containsKey(parent)) {
						for (String parentParent : parentClasses.get(parent)) {
							if (parentClasses.get(child).contains(parentParent))
								continue;
							changed = true;
							if (!changeSet.containsKey(child))
								changeSet.put(child, new HashSet<String>());
							changeSet.get(child).add(parentParent);
						}
					}

				}
			}

			for (String child : changeSet.keySet()) {
				parentClasses.get(child).addAll(changeSet.get(child));
			}

		}

		System.out.println("SportsEvent: " + parentClasses.get("SportsEvent"));
		System.out.println("TennisPlayer: " + parentClasses.get("TennisPlayer"));
		System.out.println("Fish: " + parentClasses.get("Fish"));
		System.out.println("Species: " + parentClasses.get("Species"));
		System.out.println("Taxon: " + parentClasses.get("Taxon"));

		System.out.println("\tDone.");

	}

	public Map<String, Set<String>> getTypesPerEntity() {
		return typesPerEntity;
	}

	public Map<String, Set<String>> getWikidataToDBO() {
		return wikidataToDBO;
	}

}
