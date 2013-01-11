import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ibm.icu.math.BigDecimal;
import com.vividsolutions.jts.algorithm.LineIntersector;
import com.vividsolutions.jts.algorithm.RobustLineIntersector;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;


public class Cat2OsmUtils {

	private volatile static long idnode = -1;    // Comienzo de id de nodos
	private volatile static long idway = -1;     // Comienzo de id de ways
	private volatile static long idrelation = -1; // Comienzo de id de relations

	// Fecha actual, leida del archivo .cat
	private static long fechaArchivos;

	// Lista de nodos para evitar repetidos y agrupadas por codigos de masa
	private final ConcurrentHashMap <String, ConcurrentHashMap <NodeOsm, Long>> totalNodes = 
			new ConcurrentHashMap <String, ConcurrentHashMap<NodeOsm, Long>>();
	// Listaa de ways para manejar los que se comparten y agrupadas por codigos de masa
	private final ConcurrentHashMap <String,ConcurrentHashMap <WayOsm, Long>> totalWays = 
			new ConcurrentHashMap <String, ConcurrentHashMap <WayOsm, Long>>();
	// Listaa de relations
	private final ConcurrentHashMap <String, ConcurrentHashMap <RelationOsm, Long>> totalRelations = 
			new ConcurrentHashMap <String, ConcurrentHashMap <RelationOsm, Long>>();

	// Booleanos para el modo de calcular las entradas o ver todos los Elemtex y sacar los Usos de los
	// inmuebles que no se pueden asociar
	private static boolean onlyEntrances = true; // Solo utilizara los portales de elemtex, en la ejecucion normal solo se usan esos.
	private static boolean onlyUsos = false; // Para la ejecucion de mostrar usos, se pone a true
	private static boolean onlyConstru = false; // Para la ejecucion de mostrar construs, se pone a true

	public synchronized ConcurrentHashMap <String, ConcurrentHashMap<NodeOsm, Long>> getTotalNodes() {
		return totalNodes;
	}

	public synchronized void addNode(String codigo, NodeOsm n, Long idnode){
		if (totalNodes.get(codigo) == null)
			totalNodes.put(codigo, new ConcurrentHashMap<NodeOsm, Long>());
		totalNodes.get(codigo).put(n, idnode);
	}

	public synchronized ConcurrentHashMap <String, ConcurrentHashMap<WayOsm, Long>> getTotalWays() {
		return totalWays;
	}


	public synchronized void addWay(String codigo, WayOsm w, Long idway){
		if (totalWays.get(codigo) == null)
			totalWays.put(codigo, new ConcurrentHashMap<WayOsm, Long>());
		totalWays.get(codigo).put(w, idway);
	}


	/** A la hora de simplificar, hay ways que se eliminan porque sus nodos se concatenan
	 * a otro way. Borramos los ways que no se vayan a usar de las relaciones que los contenian
	 * @param key Codigo de masa
	 * @param w Way a borrar
	 */
	public synchronized void deleteWayFromRelations(String key, WayOsm w){

		for (RelationOsm relation : totalRelations.get(key).keySet())
			relation.removeMember(totalWays.get(key).get(w));
	}


	/** Metodo para truncar las coordenadas de los shapefiles para eliminar nodos practicamente duplicados
	 * @param d numero
	 * @param decimalPlace posicion a truncar
	 * @return
	 */
	public static double round(double d, int decimalPlace) {
		BigDecimal bd = new BigDecimal(d);
		bd = bd.setScale(decimalPlace, BigDecimal.ROUND_HALF_UP);
		return bd.doubleValue();
	}


	/** Junta dos ways en uno.
	 * @param w1 Way1 Dependiendo del caso se eliminara un way o el otro
	 * @param w2 Way2
	 * @return long Way que hay que eliminar de los shapes, porque sus nodos se han juntado al otro
	 */
	public synchronized WayOsm joinWays(String key, WayOsm w1, WayOsm w2){

		if ( !w1.getNodes().isEmpty() && !w2.getNodes().isEmpty()){

			WayOsm w3;
			long idWay1;
			List<Long> nodes;

			if (totalWays.get(key).get(w1) != null && totalWays.get(key).get(w2) != null)

				switch(areConnected(w1, w2)){

				// Caso1: w1.final = w2.primero
				case 1:	
					// Clonamos el way al que le anadiremos los nodos, w1
					idWay1 = totalWays.get(key).get(w1);
					w3 = new WayOsm(null);
					for (Long lo : w1.getNodes())
						w3.addNode(lo);
					w3.setShapes(w1.getShapes());

					// Copiamos la lista de nodos del way que eliminaremos, w2
					nodes = new ArrayList<Long>();
					for (Long lo : w2.getNodes())
						nodes.add(lo);

					// Eliminamos el nodo que comparten de la lista de nodos
					nodes.remove(w2.getNodes().get(0));

					// Concatenamos al final del way3 (copia del way1) los nodos del way2
					w3.addNodes(nodes);

					// Borramos el w1 del mapa de ways porque se va a meter el w3 (que es el w1 con los nuevos
					// nodos concatenados)
					totalWays.get(key).remove(w1);

					// Borramos el w2 de las relaciones pero no del mapa porque hace falta para el return
					deleteWayFromRelations(key, w2);

					// Guardamos way3 en la lista de ways, manteniendo el id del way1
					totalWays.get(key).put(w3, idWay1);

					return w2;

					// Caso2: w1.primero = w2.final
				case 2:

					// Es igual que el Caso1 pero cambiados de orden.
					return joinWays(key, w2, w1);

					// Caso3: w1.primero = w2.primero
				case 3:

					// Clonamos el way al que le anadiremos los nodos, w1
					idWay1 = totalWays.get(key).get(w1);
					w3 = new WayOsm(null);
					for (Long lo : w1.getNodes())
						w3.addNode(lo);
					w3.setShapes(w1.getShapes());

					// Copiamos la lista de nodos del way que eliminaremos, w2
					nodes = new ArrayList<Long>();
					for (Long lo : w2.getNodes())
						nodes.add(lo);

					// Eliminamos el nodo que comparten de la lista de nodos
					nodes.remove(w2.getNodes().get(0));

					// Damos la vuelta a la lista de nodos que hay que concatenar en la posicion 0 del
					// way que vamos a conservar
					Collections.reverse(nodes);

					// Concatenamos al principio del way3 (copia del way1) los nodos del way2
					w3.addNodes(nodes, 0);

					// Borramos el w1 del mapa de ways porque se va a meter el w3 (que es el w1 con los nuevos
					// nodos concatenados)
					totalWays.get(key).remove(w1);

					// Borramos el w2 de las relaciones pero no del mapa porque hace falta para el return
					deleteWayFromRelations(key, w2);

					// Guardamos way3 en la lista de ways, manteniendo el id del way1
					totalWays.get(key).put(w3, idWay1);

					return w2;

					// Caso4: w1.final = w2.final
				case 4:

					// Es igual que el Caso3 pero invirtiendo las dos vias
					w1.reverseNodes();
					w2.reverseNodes();

					return joinWays(key, w1, w2);

				case 0:
					// Si el id de alguna via ya no esta en el mapa de vias
					if (totalWays.get(key).get(w1) == null){

						// Borramos el way de las relaciones
						deleteWayFromRelations(key, w1);
					}
					else if (totalWays.get(key).get(w2) == null){

						// Borramos el way de las relaciones
						deleteWayFromRelations(key, w2);
					}

				}
		}
		return null;
	}

	public synchronized ConcurrentHashMap< String, ConcurrentHashMap<RelationOsm, Long>> getTotalRelations() {
		return totalRelations;
	} 

	public synchronized void addRelation(String codigo, RelationOsm r, Long idrel){
		if (totalRelations.get(codigo) == null)
			totalRelations.put(codigo, new ConcurrentHashMap<RelationOsm, Long>());
		totalRelations.get(codigo).put(r, idrel);
	}


	/**	Mira si existe un nodo con las mismas coordenadas
	 * de lo contrario crea el nuevo nodo. Despues devuelve el id
	 * @param key Clave en el mapa de masas
	 * @param c Coordenada del nodo para comparar si ya existe otro
	 * @param shape Shape al que pertenece DIRECTAMENTE ese nodo. Solamente para Elemtex y Elempun para que
	 * luego vaya a ellos a coger los tags
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public synchronized long generateNodeId(String key, Coordinate c, Shape shape){

		Coordinate coor = new Coordinate(round(c.x,7), round(c.y,7));

		Long id = null;

		if (totalNodes.get(key) == null)
			totalNodes.put(key, new ConcurrentHashMap<NodeOsm, Long>());

		if (!totalNodes.get(key).isEmpty())
			id = totalNodes.get(key).get(new NodeOsm(coor));

		// Existe el nodo
		if (id != null){

			// Si es un nodo que hemos creado porque depende de un way, entonces no le indicamos shape
			// Por lo contrario si es un nodo de un shape puntual, indicamos a que shape pertenece, para que luego
			// coja los tags de el
			if(shape != null)
				((NodeOsm) getKeyFromValue((Map< String, Map<Object, Long>>) ((Object) totalNodes), key, id)).addShape(shape);

			return id;
		}
		// No existe, por lo que creamos uno
		else{
			idnode--;
			NodeOsm n = new NodeOsm(coor);

			// Si es un nodo que hemos creado porque depende de un way, entonces no le indicamos shape
			// Por lo contrario si es un nodo de un shape puntual, indicamos a que shape pertenece, para que luego
			// coja los tags de el
			if(shape != null)
				n.addShape(shape);

			totalNodes.get(key).putIfAbsent(n, idnode);
			return idnode;
		}
	}


	/** Mira si existe un way con los mismos nodos y en ese caso anade
	 * los tags, de lo contrario crea uno. Despues devuelve el id
	 * @param key Codigo de masa en la que esta el way
	 * @param nodes Lista de nodos
	 * @param shapes Lista de los shapes a los que pertenecera
	 * @return devuelve el id del way creado o el del que ya existia
	 */
	@SuppressWarnings("unchecked")
	public synchronized long generateWayId(String key, List<Long> nodes, Shape shape ){

		Long id = null;

		if (totalWays.get(key) == null)
			totalWays.put(key, new ConcurrentHashMap<WayOsm, Long>());

		if (!totalWays.isEmpty())
			id = totalWays.get(key).get(new WayOsm(nodes));

		// Existe el way
		if (id != null){
			((WayOsm) getKeyFromValue((Map< String, Map<Object, Long>>) ((Object) totalWays), key, id)).addShape(shape);
			return id;
		}
		// No existe el way por lo que lo creamos
		else{
			idway--;
			WayOsm w = new WayOsm(nodes);
			w.addShape(shape);
			totalWays.get(key).putIfAbsent(w, idway);
			return idway;
		}
	}


	/** Mira si existe una relation con los mismos ways y en ese caso anade 
	 * los tags, de lo contrario crea una. Despues devuelve el id
	 * @param key Codigo de masa en la cual esta la relation
	 * @param ids Lista de ids de los members q componen la relacion
	 * @param types Lista de los tipos de los members de la relacion (por lo general ways)
	 * @param roles Lista de los roles de los members de la relacion (inner,outer...)
	 * @param tags Lista de los tags de la relacion
	 * @param shapesId Lista de shapes a los que pertenece
	 * @return devuelve el id de la relacion creada o el de la que ya existia
	 */
	@SuppressWarnings("unchecked")
	public synchronized long generateRelationId(String key, List<Long> ids, List<String> types, List<String> roles, Shape shape){

		Long id = null;

		if (totalRelations.get(key) == null)
			totalRelations.put(key, new ConcurrentHashMap<RelationOsm, Long>());

		if (!totalRelations.isEmpty())
			id = totalRelations.get(key).get(new RelationOsm(ids,types,roles));

		// Ya existe una relation, por lo que anadimos el shape a su lista de shapes
		if (id != null){
			((RelationOsm) getKeyFromValue((Map< String, Map<Object, Long>>) ((Object)totalRelations), key, id)).addShape(shape);
			return id;
		}
		// No existe relation que coincida por lo que la creamos
		else{
			idrelation--;
			RelationOsm r = new RelationOsm(ids,types,roles);
			r.addShape(shape);
			totalRelations.get(key).putIfAbsent(r, idrelation);
			return idrelation;
		}
	}

	/** Dado un Value de un Map devuelve su Key
	 * @param map Mapa
	 * @param codigo Codigo de masa
	 * @param id Value en el map para obtener su Key
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public synchronized Object getKeyFromValue(Map<String, Map <Object, Long>> map, String key, Long id){

		if (map.get(key) == null)
			return null;

		for (Object o: map.get(key).entrySet()) {
			Map.Entry<Object,Long> entry = (Map.Entry<Object, Long>) o;
			if(entry.getValue().equals(id))
				return entry.getKey();
		}
		return null;
	}

	/** Dada una lista de identificadores de ways, devuelve una lista con esos
	 * ways
	 * @return ways lista de WayOsm
	 */
	@SuppressWarnings("unchecked")
	public synchronized List<WayOsm> getWays(String codigo, List<Long> ids){
		List<WayOsm> ways = new ArrayList<WayOsm>();

		for (Long l: ids)
			ways.add(((WayOsm) getKeyFromValue((Map< String, Map <Object, Long>>) ((Object)totalWays), codigo, l)));

		ways.remove(null);

		return ways;
	}


	/** Dada una lista de identificadores de nodes, devuelve una lista con esos
	 * nodes
	 * @return nodes lista de NodeOsm
	 */
	@SuppressWarnings("unchecked")
	public synchronized List<NodeOsm> getNodes(String key, List<Long> ids){
		List<NodeOsm> nodes = new ArrayList<NodeOsm>();

		for (Long l: ids)
			nodes.add(((NodeOsm) getKeyFromValue((Map< String, Map <Object, Long>>) ((Object)totalNodes), key, l)));

		nodes.remove(null);

		return nodes;
	}

	/** Dada una lista de identificadores de nodes, borra esos nodes de la lista de nodos y de ways
	 * @param key Codigo de masa en la que estan esos nodes
	 * @param ids Lista de nodos
	 */
	@SuppressWarnings("unchecked")
	public synchronized void deleteNodes(String key, List<Long> ids){

		for (Long id : ids){

			NodeOsm node = ((NodeOsm) getKeyFromValue((Map< String, Map <Object, Long>>) ((Object)totalNodes), key, id));
			totalNodes.get(key).remove(node);

			for (WayOsm w : totalWays.get(key).keySet())
				w.getNodes().remove(id);
		}
	}

	public static boolean getOnlyEntrances() {
		return onlyEntrances;
	}

	public void setOnlyEntrances(boolean entrances) {
		Cat2OsmUtils.onlyEntrances = entrances;
	}

	public static boolean getOnlyUsos() {
		return onlyUsos;
	}

	public void setOnlyUsos(boolean usos) {
		Cat2OsmUtils.onlyUsos = usos;
	}

	public static boolean getOnlyConstru() {
		return onlyConstru;
	}

	public void setOnlyConstru(boolean constru) {
		Cat2OsmUtils.onlyConstru = constru;
	}

	public static boolean nodeOnWay(Coordinate node, Coordinate[] wayCoors) {
		LineIntersector lineIntersector = new RobustLineIntersector();
		for (int i = 1; i < wayCoors.length; i++) {
			Coordinate p0 = wayCoors[i - 1];
			Coordinate p1 = wayCoors[i];
			lineIntersector.computeIntersection(node, p0, p1);
			if (lineIntersector.hasIntersection()) {
				return true;
			}
		}
		return false;
	}

	public static long getFechaArchivos() {
		return fechaArchivos;
	}

	public static void setFechaArchivos(long fechaArchivos) {
		Cat2OsmUtils.fechaArchivos = fechaArchivos;
	}


	/** Calcula si 2 ways estan conectados y devuelve de que forma estan conectados
	 * @param way1 WayOsm
	 * @param way2 WayOsm
	 * @return Codigo de como estan conectados
	 * -1 = alguna via nula
	 * 0 = no conectados
	 * 1 = Caso1: w1.final = w2.primero
	 * 2 = Caso2: w1.primero = w2.final
	 * 3 = Caso3: w1.primero = w2.primero
	 * 4 = Caso4: w1.final = w2.final
	 */
	public static int areConnected(WayOsm w1, WayOsm w2){

		if(w1 == null || w2 == null)
			return -1;
		if(w1.getNodes().get(w1.getNodes().size()-1).equals(w2.getNodes().get(0)))
			return 1;
		if(w1.getNodes().get(0).equals(w2.getNodes().get(w2.getNodes().size()-1)))
			return 2;
		if(w1.getNodes().get(0).equals(w2.getNodes().get(0)))
			return 3;
		if(w1.getNodes().get(w1.getNodes().size()-1).equals(w2.getNodes().get(w2.getNodes().size()-1)))
			return 4;
		return 0;	
	}

	public static int areConnected(Geometry g1, Geometry g2){

		if(g1 == null || g2 == null)
			return -1;
		if(g1.getCoordinates()[g1.getCoordinates().length-1].equals(g2.getCoordinates()[0]))
			return 1;
		if(g1.getCoordinates()[0].equals(g2.getCoordinates()[g2.getCoordinates().length-1]))
			return 2;
		if(g1.getCoordinates()[0].equals(g2.getCoordinates()[0]))
			return 3;
		if(g1.getCoordinates()[g1.getCoordinates().length-1].equals(g2.getCoordinates()[g2.getCoordinates().length-1]))
			return 4;
		return 0;	
	}


	/** Metodo para parsear los shapes cuyas geografias vienen dadas como
	 * MultiPolygon, como MASA.SHP, PARCELA.SHP, SUBPARCE.SHP y CONSTRU.SHP
	 * Asigna los valores al shape, sus nodos, sus ways y relation
	 * @param shape Shape creado pero sin los valores de los nodos, ways o relation
	 * @return boolean si se ha podido parsear
	 */
	@SuppressWarnings("unchecked")
	public boolean mPolygonShapeParser(Shape shape){
		
		// Caso general
		if (!shape.getGeometry().isEmpty()){
			
			// Obtenemos las coordenadas de cada punto del shape
			if(shape.getGeometry().getNumGeometries() == 1){
				
				int numPolygons = 0;
				
				for (int x = 0; x < shape.getGeometry().getNumGeometries(); x++){
					Polygon p = (Polygon) shape.getGeometry().getGeometryN(x);
					
					// Outer
					Coordinate[] coors = p.getExteriorRing().getCoordinates();
					numPolygons++;
					// Miramos por cada punto si existe un nodo, si no lo creamos
					for (Coordinate coor : coors){
						// Insertamos en la lista de nodos del shape, los ids de sus nodos
						shape.addNode(0, generateNodeId(shape.getCodigoMasa(), coor, null));
					}
					
					// Posibles Inners
					for (int y = 0; y < p.getNumInteriorRing(); y++){
						coors = p.getInteriorRingN(y).getCoordinates();
						
						numPolygons++;
						
						// Miramos por cada punto si existe un nodo, si no lo creamos
						for (Coordinate coor : coors){
							// Insertamos en la lista de nodos del shape, los ids de sus nodos
							shape.addNode(y+1, generateNodeId(shape.getCodigoMasa(), coor, null));
						}
					}
				}

				// Por cada poligono creamos su way
				for (int y = 0; y < numPolygons; y++){
					// Con los nodos creamos un way
					List <Long> nodeList = shape.getNodesIds(y);
					shape.addWay(y, generateWayId(shape.getCodigoMasa(), nodeList, null));
				}

				// Creamos una relation para el shape, metiendoe en ella todos los members
				List <Long> ids = new ArrayList<Long>(); // Ids de los members
				List <String> types = new ArrayList<String>(); // Tipos de los members
				List <String> roles = new ArrayList<String>(); // Roles de los members
				for (int y = 0; y < shape.getWays().size(); y++){
					long wayId = shape.getWays().get(y);
						if (!ids.contains(wayId)){
							ids.add(wayId);
							types.add("way");
							if (y == 0)roles.add("outer");
							else roles.add("inner");
						}
				}
				
				shape.setRelation(generateRelationId(shape.getCodigoMasa(), ids, types, roles, shape));
				
				
				// Caso especial de ShapeParcela que esta contiene varios shapes dentro
				if(shape instanceof ShapeParcela){
					
					RelationOsm relation = (RelationOsm) getKeyFromValue( (Map<String, Map <Object, Long>>) ((Object)getTotalRelations()), shape.getCodigoMasa(), shape.getRelationId());
					
//					if (((ShapeParcela) shape).getEntrance() != null){
//						pointShapeParser(((ShapeParcela) shape).getEntrance());
//						
//						// Incluimos el elemento en la relation de su parcela
//						relation.getIds().add(((ShapeParcela) shape).getEntrance().getNodesIds(0).get(0));
//						relation.getTypes().add("node");
//						relation.getRoles().add("outer");
//					}

					if (((ShapeParcela) shape).getSubshapes() != null)
						for(ShapePolygonal subshape : ((ShapeParcela) shape).getSubshapes()){
							boolean parsed = mPolygonShapeParser(subshape);

							// Comprobamos que una parcela no meta un edificio con la misma geometria
							// como relacion suya. (Al tener la misma geometria tienen el mismo id de
							// relation y este caso ya se contempla al imprimir)
							if(parsed && shape.getRelationId().longValue() != subshape.getRelationId().longValue()){
								// Incluimos el elemento en la relation de su parcela
								// pero teniendo en cuenta que si solo tiene 1 way esa relation se convertira en way
								relation.getIds().add(subshape.getRelationId());
								relation.getTypes().add("relation");
								relation.getRoles().add("outer");
							}
						}
				}
				
				return true;
			}
			else
				System.out.println("["+new Timestamp(new Date().getTime())+"] Shape con mas de una geometria : "+ shape.getGeometry().getNumGeometries());
		}

		return false;
	}


	/** Metodo para parsear los shapes cuyas geografias vienen dadas como Point
	 * o MultiLineString pero queremos solo un punto, como ELEMPUN.SHP y ELEMTEX.SHP
	 * Asigna los valores al shape y su unico nodo
	 * @param shape Shape creado pero sin el valor del nodo
	 * @return boolean si se ha podido parsear
	 */
	public boolean pointShapeParser(Shape shape){

		if (!shape.getGeometry().isEmpty()){
			Coordinate coor = shape.getGeometry().getCoordinate();

			// Anadimos solo un nodo
			shape.addNode(0, generateNodeId(shape.getCodigoMasa(), coor, shape));

			return true;
		}
		return false;
	}


	/** Metodo para parsear los shapes cuyas geografias vienen dadas como
	 * MultiLineString, como ELEMLIN.SHP y EJES.SHP
	 * Asigna los valores al shape, sus nodos, sus ways y relation
	 * @param shape Shape creado pero sin los valores de los nodos, ways o relation
	 * @return boolean si se ha podido parsear
	 */
	public boolean mLineStringShapeParser(Shape shape){

		if(!shape.getGeometry().isEmpty()){

			// Anadimos todos los nodos
			Coordinate[] coor = shape.getGeometry().getCoordinates();

			for (int x = 0; x < coor.length; x++){
				shape.addNode(0, generateNodeId(shape.getCodigoMasa(), coor[x], null));
			}

			// Con los nodos creamos un way
			List <Long> nodeList = shape.getNodesIds(0);
			shape.addWay(0, generateWayId(shape.getCodigoMasa(), nodeList, shape));

			return true;
		}
		return false;
	}




}
