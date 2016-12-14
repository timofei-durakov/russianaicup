import model.*;

import java.util.*;

enum Danger {
    LOW,
    NORMAL,
    HIGH;
}

public final class MyStrategy implements Strategy {
    private static final double WAYPOINT_RADIUS = 200.0D;

    private static final double LOW_HP_FACTOR_SKILLS = 0.30D;
    private static final double HIGH_HP_FACTOR_SKILLS = 0.90D;

    private static final double LOW_HP_FACTOR_NO_SKILLS = 0.20D;
    private static final double HIGH_HP_FACTOR_NO_SKILLS = 0.90D;
    /**
     * Ключевые точки для каждой линии, позволяющие упростить управление перемещением волшебника.
     * <p/>
     * Если всё хорошо, двигаемся к следующей точке и атакуем противников.
     * Если осталось мало жизненной энергии, отступаем к предыдущей точке.
     */
    private final Map<LaneType, Point2D[]> waypointsByLane = new EnumMap<>(LaneType.class);

    private Random random;
    private double previousHealth;
    private int tickWithDamage = 0;
    private LaneType lane;
    private Point2D[] waypoints;
    private int previousLevel;
    private Wizard self;
    private World world;
    private Game game;
    private Move move;
    private double angles[] = getAngles();
    private Map<IntPoint, BuildingMark> buildingMarks;
    private int friendlyWizards = 1;
    private int enemyWizards = 0;
    private int enemyFetish = 0;
    private List<BuildingMark> marksToCheck;
    private Map<IntPoint, Building> enemyBuildings;
    private DamageCostComparator damageCostComparator = new DamageCostComparator();
    private MoveCostComparator moveCostComparator = new MoveCostComparator();
    private AttackInterestCostComparator attackInterestCostComparator = new AttackInterestCostComparator();
    private boolean fireBall = false;
    static int ANGLES_FACTOR = 36;
    static double MOVE_RADIUS = 50.0;
    static double ATTACK_RADIUS = 4.0;
    static String NEUTRAL_UNITS = "NEUTRAL_UNITS";
    static String FRIENDLY_WIZARDS = "FRIENDLY_WIZARDS";
    static String ENEMIES = "ENEMIES";
    static String ENEMY_WIZARDS = "ENEMY_WIZARDS";
    static String FRIENDLY_MINIONS = "FRIENDLY_MINIONS";
    static String ENEMY_MINIONS = "ENEMY_MINIONS";
    static String FRIENDLY_BUILDINGS = "FRIENDLY_BUILDINGS";
    static String ENEMY_BUILDINGS = "ENEMY_BUILDINGS";
    static String TREES = "TREES";

    private double[] getAngles() {
        double[] angles = new double[ANGLES_FACTOR];
        double sector = StrictMath.PI * 2.0 / 36.0d;
        for (int i = 0; i < ANGLES_FACTOR; i++) {
            angles[i] = sector * i;
        }
        return angles;
    }

    private void getEnemyBuildingsMarks(World world) {
        Building[] buildingsToMirror = world.getBuildings();
        Map<IntPoint, BuildingMark> buildingMarks = new HashMap<>();
        for (Building b : buildingsToMirror) {
            IntPoint point = new IntPoint((int) Math.round(4000 - b.getX()), (int) Math.round(4000 - b.getY()));
            Point2D point2D = new Point2D(4000 - b.getX(), 4000 - b.getY());
            BuildingType type = b.getType();
            BuildingMark mark = new BuildingMark(type, point2D);
            buildingMarks.put(point, mark);
        }
        this.buildingMarks = buildingMarks;
    }

    private Point2D getPotentialPositionForUnit(LivingUnit unit) {
        Point2D result = null;
        double speedX = unit.getSpeedX();
        double speedY = unit.getSpeedY();
        if (speedX != 0.0 || speedY != 0.0) {
            result = new Point2D(unit.getX() + speedX, unit.getY() + speedY);
        }
        return result;
    }

    private List<Point2D> getNextPoints(Point2D point, double radius) {
        List<Point2D> points = new ArrayList<>(ANGLES_FACTOR);
        int i = 0;
        for (double angle : angles) {
            double x = StrictMath.cos(angle) * radius;
            double y = StrictMath.sin(angle) * radius;
            Point2D point2d = new Point2D(point.x + x, point.y + y);
            if (point2d.isValid()) {
                points.add(point2d);
            } else {
                points.add(null);
            }
        }
        return points;
    }

    private List<Point2D> getNextMovePoints(Point2D point2D) {
        return getNextPoints(point2D, MOVE_RADIUS);
    }

    private List<Point2D> getNextAttackPoints(Point2D point2D) {
        return getNextPoints(point2D, ATTACK_RADIUS);
    }

    private double[] getSpeedForStrafe(double obstacleAngle) {
        double moveSpeed = 0.0;
        double strafeSpeed = 0.0;
        double moveFactor = StrictMath.cos(obstacleAngle);
        double strafeFactor = StrictMath.sin(obstacleAngle);
        moveSpeed = game.getWizardForwardSpeed() * moveFactor;
        strafeSpeed = game.getWizardStrafeSpeed() * strafeFactor;
        return new double[]{moveSpeed, strafeSpeed};
    }

    private boolean unitOverlapsWithSelf(Point2D point, Point2D attackPoint, List<LivingUnit> units) {
        for (LivingUnit unit : units) {
            double overlapRadius = self.getRadius() + unit.getRadius();
            if (point.getDistanceTo(unit) <= overlapRadius || attackPoint.getDistanceTo(unit) <= overlapRadius) {
                return true;
            }
        }
        return false;
    }

    private double getWizardsDamageForPoint(Point2D point2D, List<LivingUnit> wizards, Danger danger) {
        double potentialWizardDamage = 0.0;
        for (LivingUnit w : wizards) {
            potentialWizardDamage += getWizardDamageForPoint(point2D, w, danger);
        }
        return potentialWizardDamage;
    }

    private double getWizardDamageForPoint(Point2D point2D, LivingUnit w, Danger danger) {
        double staffDamage = game.getStaffDamage();
        double staffRange = game.getWizardCastRange();
        double dangerRatio = danger == Danger.HIGH ? 1.3 : 1.0;
        dangerRatio = danger == Danger.LOW ? 0.8 : dangerRatio;
        double attackRadius = (staffRange + self.getRadius()) * dangerRatio;
        double attackSector = danger == Danger.HIGH ? game.getStaffSector() * 10 : game.getStaffSector();
        Wizard wizard = (Wizard) w;
        double distance = point2D.getDistanceTo(w);
        if (distance > attackRadius * 2.0) {
            return 0.0;
        }
        Point2D potentialPosition = getPotentialPositionForUnit(w);
        double potentialPositionDistance = Double.MAX_VALUE;
        if (potentialPosition != null) {
            potentialPositionDistance = point2D.getDistanceTo(potentialPosition);
        }
        if (wizard.getRemainingActionCooldownTicks() > 40) {
            return 0.0;
        }
        double angle = w.getAngleTo(point2D.x, point2D.y);
        if (distance <= attackRadius || potentialPositionDistance <= attackRadius) {
            if (StrictMath.abs(angle) < game.getStaffSector() / 2.0 ||
                    StrictMath.abs(angle) - game.getWizardMaxTurnAngle() < attackSector) {
                return staffDamage;
            }
        }

        return 0.0;
    }

    private double getMinionsDamageForPoint(Point2D point2D, List<LivingUnit> minions, Danger danger) {
        double potentialMinionDamage = 0.0;
        for (LivingUnit lu : minions) {
            potentialMinionDamage += getMinionDamageForPoint(point2D, lu, danger);
        }
        return potentialMinionDamage;
    }

    private double getMinionDamageForPoint(Point2D point2D, LivingUnit m, Danger danger) {
        double dartDamage = game.getDartDirectDamage();
        double dartRange = game.getFetishBlowdartAttackRange() + game.getMinionRadius() + self.getRadius();
        double dartSector = danger == Danger.HIGH ? game.getFetishBlowdartAttackSector() * 10 :
                game.getFetishBlowdartAttackSector();
        double dangerRatio = danger == Danger.HIGH ? 1.3 : 1.0;
        double dartAttackRadius = dartRange * dangerRatio;
        double woodCutterDamage = game.getOrcWoodcutterDamage();
        double woodCutterRange = game.getOrcWoodcutterAttackRange();
        double woodCutterAttackRadius = (woodCutterRange + 150) * dangerRatio;
        double woodCutterSector = danger == Danger.HIGH ? game.getOrcWoodcutterAttackSector() * 10 :
                game.getOrcWoodcutterAttackSector();

        Minion minion = (Minion) m;
        Point2D potentialPosition = getPotentialPositionForUnit(m);
        double distance = point2D.getDistanceTo(minion);
        double potentialPositionDistance = Double.MAX_VALUE;
        if (potentialPosition != null) {
            potentialPositionDistance = point2D.getDistanceTo(potentialPosition);
        }
        double angle = minion.getAngleTo(point2D.x, point2D.y);
        if (minion.getType() == MinionType.FETISH_BLOWDART) {
            if (minion.getRemainingActionCooldownTicks() > 10) {
                return 0.0;
            }
            if (distance <= dartAttackRadius || potentialPositionDistance <= dartAttackRadius) {
                if (StrictMath.abs(angle) < game.getFetishBlowdartAttackSector() / 2.0D ||
                        StrictMath.abs(angle) - game.getFetishBlowdartAttackSector() < dartSector) {
                    return dartDamage;
                }
            }
        } else {
            if (distance <= woodCutterAttackRadius || potentialPositionDistance <= woodCutterAttackRadius) {
                if (StrictMath.abs(angle) < game.getOrcWoodcutterAttackSector() / 2.0D ||
                        StrictMath.abs(angle) - game.getOrcWoodcutterAttackSector() < woodCutterSector) {
                    return woodCutterDamage;
                }
            }
        }

        return 0.0;
    }

    private double getBuildingMarksDamageForPoint(Point2D point2D, List<BuildingMark> buildings,
                                                  Danger danger) {
        double potentialBuildingDamage = 0.0;
        for (BuildingMark lu : buildings) {
            potentialBuildingDamage += getBuildingMarkDamageForPoint(point2D, lu, danger);
        }
        return potentialBuildingDamage;
    }

    private double getBuildingMarkDamageForPoint(Point2D point2D, BuildingMark b, Danger danger) {
        double towerDamage = game.getGuardianTowerDamage();
        double towerRange = game.getGuardianTowerAttackRange();
        double baseDamage = game.getFactionBaseDamage();
        double baseRange = game.getFactionBaseAttackRange();
        double dangerRatio = danger == Danger.HIGH ? 1.3 : 1.0;
        double distance = point2D.getDistanceTo(b);
        if (b.getBuildingType() == BuildingType.FACTION_BASE) {
            if (distance <= (game.getFactionBaseRadius() + baseRange) * dangerRatio) {
                return baseDamage;
            }
        } else if (b.getBuildingType() == BuildingType.GUARDIAN_TOWER) {
            if (distance <= (game.getGuardianTowerRadius() + towerRange) * dangerRatio) {
                return towerDamage;
            }
        }
        return 0.0;
    }

    private double getBuildingsDamageForPoint(Point2D point2D, List<LivingUnit> buildings,
                                              Danger danger) {
        double potentialBuildingDamage = 0.0;
        for (LivingUnit lu : buildings) {
            potentialBuildingDamage += getBuildingDamageForPoint(point2D, lu, danger);
        }
        return potentialBuildingDamage;
    }

    private double getBuildingDamageForPoint(Point2D point2D, LivingUnit b,
                                             Danger danger) {
        double towerDamage = game.getGuardianTowerDamage();
        double towerRange = game.getGuardianTowerAttackRange();
        double baseDamage = game.getFactionBaseDamage();
        double baseRange = game.getFactionBaseAttackRange();
        double dangerRatio = danger == Danger.HIGH ? 1.3 : 1.0;
        Building building = (Building) b;
        double distance = point2D.getDistanceTo(building);

        if (building.getRemainingActionCooldownTicks() > 100 && distance > 460) {
            return 0.0;
        }

//        if (building.getType() == BuildingType.FACTION_BASE) {
//            if (distance <= (game.getFactionBaseRadius() + baseRange) * dangerRatio) {
//                return baseDamage;
//            }
//        } else {
        if (building.getType() != BuildingType.FACTION_BASE) {
            if (distance <= (game.getGuardianTowerRadius() + towerRange) * dangerRatio) {
                return towerDamage;
            }
        }


        return 0.0;
    }


    private List<Point2D> weightCollisions(List<Point2D> movePoints, List<Point2D> attackPoints,
                                           Map<String, List<LivingUnit>> objectsMap) {
        List<Point2D> result = new ArrayList<>();
        for (int counter = 0; counter < movePoints.size(); counter++) {
            Point2D point = movePoints.get(counter);
            Point2D attackPoint = attackPoints.get(counter);
            if (point == null) {
                continue;
            }
            boolean hasCollisions = false;
            for (Map.Entry<String, List<LivingUnit>> entry : objectsMap.entrySet()) {
                //Skipping aggregated enemies list
                if (entry.getKey().equals(ENEMIES)) {
                    continue;
                }

                if (unitOverlapsWithSelf(point, attackPoint, entry.getValue())) {
                    hasCollisions = true;
                    break;
                }
            }
            if (!hasCollisions) {
                result.add(attackPoint);
            }
        }
        return result;
    }

    private void weightFightPoints(List<Point2D> trackPoints, Map<String, List<LivingUnit>> objectsMap,
                                   Danger danger) {
        for (Point2D point2D : trackPoints) {
            for (Map.Entry<String, List<LivingUnit>> objects : objectsMap.entrySet()) {
                String key = objects.getKey();
                List<LivingUnit> units = objects.getValue();
                if (ENEMY_WIZARDS.equals(key)) {
                    point2D.appendToDamageInterest(getWizardsDamageForPoint(point2D, units, danger));

                } else if (ENEMY_MINIONS.equals(key)) {
                    point2D.appendToDamageInterest(getMinionsDamageForPoint(point2D, units, danger));
                } else if (ENEMY_BUILDINGS.equals(key)) {
                    point2D.appendToDamageInterest(getBuildingsDamageForPoint(point2D, units, danger));
                }

            }
            if (danger != Danger.LOW) {
                point2D.appendToDamageInterest(getBuildingMarksDamageForPoint(point2D, marksToCheck, danger));
            }
        }

    }

    private void weightDistanceToWayPoint(List<Point2D> trackPoints, Point2D wayPoint) {
        for (Point2D point2D : trackPoints) {
            point2D.appendToMoveInterest(point2D.getDistanceTo(wayPoint));
        }
    }

    private Danger getDangerSkills() {
        if (self.getLife() < self.getMaxLife() * LOW_HP_FACTOR_SKILLS) {
            return Danger.HIGH;
        } else if (self.getLife() >= self.getMaxLife() * HIGH_HP_FACTOR_SKILLS) {
            return Danger.LOW;
        } else {
            return Danger.NORMAL;
        }
    }

    private Danger getDangerNoSkills() {
        if (self.getLife() < self.getMaxLife() * LOW_HP_FACTOR_NO_SKILLS) {
            return Danger.HIGH;
        } else if (self.getLife() >= self.getMaxLife() * HIGH_HP_FACTOR_NO_SKILLS) {
            return Danger.LOW;
        } else {
            return Danger.NORMAL;
        }
    }

    private void cleanBuildingMarks(Map<String, List<LivingUnit>> objectsMap) {
        List<IntPoint> destroyedBuildings = new ArrayList<>();
        Set<IntPoint> existingBuildings = new HashSet<>();
        marksToCheck = new ArrayList<>();
        for (Map.Entry<IntPoint, BuildingMark> buildingMarkEntry : buildingMarks.entrySet()) {
            for (Map.Entry<String, List<LivingUnit>> entry : objectsMap.entrySet()) {
                String key = entry.getKey();
                List<LivingUnit> units = entry.getValue();
                boolean stopSearching = false;
                if (FRIENDLY_MINIONS.equals(key) || FRIENDLY_WIZARDS.equals(key)) {
                    double visionRange;
                    if (FRIENDLY_MINIONS.equals(key)) {
                        visionRange = game.getMinionVisionRange();
                    } else {
                        visionRange = game.getWizardVisionRange();
                    }
                    for (LivingUnit unit : units) {
                        if (buildingMarkEntry.getKey().getDistanceTo(unit) < visionRange) {
                            if (!enemyBuildings.containsKey(buildingMarkEntry.getKey())) {
                                destroyedBuildings.add(buildingMarkEntry.getKey());
                            } else {
                                existingBuildings.add(buildingMarkEntry.getKey());
                            }
                            stopSearching = true;
                            break;
                        }

                    }
                }
                if (stopSearching) {
                    break;
                }
            }
        }
        for (IntPoint p : destroyedBuildings) {
            buildingMarks.remove(p);

        }
        for (Map.Entry<IntPoint, BuildingMark> buildingMarkEntry : buildingMarks.entrySet()) {
            if (!existingBuildings.contains(buildingMarkEntry.getKey())) {
                marksToCheck.add(buildingMarkEntry.getValue());
            }
        }
    }

    int[] skillsToLearn = {10, 11, 12, 13, 14, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 18, 19};

    /**
     * Основной метод стратегии, осуществляющий управление волшебником.
     * Вызывается каждый тик для каждого волшебника.
     *
     * @param self  Волшебник, которым данный метод будет осуществлять управление.
     * @param world Текущее состояние мира.
     * @param game  Различные игровые константы.
     * @param move  Результатом работы метода является изменение полей данного объекта.
     */
    @Override
    public void move(Wizard self, World world, Game game, Move move) {
        initializeStrategy(self, game, world);
        initializeTick(self, world, game, move);
        if (self.getLevel() > previousLevel) {
            SkillType skillToLearn = SkillType.values()[skillsToLearn[previousLevel]];
            if (skillToLearn == SkillType.FIREBALL) {
                fireBall = true;
            }
            move.setSkillToLearn(skillToLearn);
        }
        if (self.getLife() < previousHealth) {
            tickWithDamage = world.getTickIndex();
        }
        List<Point2D> movePoints = getNextMovePoints(new Point2D(self));
        List<Point2D> attackPoints = getNextAttackPoints(new Point2D(self));
        Map<String, List<LivingUnit>> nearest = getNearest();
        cleanBuildingMarks(nearest);
        //Filter potential collisions here
        List<Point2D> trackPoints = weightCollisions(movePoints, attackPoints, nearest);

        Point2D nextWP = getNextWaypoint();
        Point2D prevWP = getPreviousWaypoint();
        Danger danger;
        if (game.isSkillsEnabled()) {
            danger = getDangerSkills();
        } else {
            danger = getDangerNoSkills();
        }
        if (danger == Danger.HIGH) {
            weightFightPoints(trackPoints, nearest, danger);
            trackPoints.sort(damageCostComparator);
            if (!trackPoints.isEmpty() &&
                    trackPoints.get(0).getDamageInterest() > 0 &&
                    tickWithDamage + 75 >= world.getTickIndex()) {
                weightDistanceToWayPoint(trackPoints, prevWP);
                trackPoints.sort(moveCostComparator);
                goToPoint(trackPoints, null, false);
                previousLevel = self.getLevel();
                previousHealth = self.getLife();
                return;
            }
        }

        List<LivingUnit> enemies = nearest.get(ENEMIES);
        LivingUnit closestEnemy = enemies.size() > 0 ? enemies.get(0) : null;
        Point2D currentPosition = new Point2D(self);
        trackPoints.add(currentPosition);
        weightFightPoints(trackPoints, nearest, danger);
        trackPoints.sort(damageCostComparator);

        Point2D target = null;
        if (!trackPoints.isEmpty()) {
            target = trackPoints.get(0);
        }
        //Most safe point is still unsafe

        if (target != null && target.getDamageInterest() > 0) {
            weightDistanceToWayPoint(trackPoints, prevWP);
            trackPoints.sort(moveCostComparator);
            target = trackPoints.get(0);
            Enemy enemy = getTarget(target, enemies, danger, false);
            if (enemy != null) {
                closestEnemy = enemy.getUnit();
            }
            goTo(target, closestEnemy, true);
            previousLevel = self.getLevel();
            previousHealth = self.getLife();
            return;
        }
        if (closestEnemy != null && (target == null || self.getDistanceTo(closestEnemy) < self.getCastRange())) {
            target = null;
            Enemy currentEnemy = getTarget(currentPosition, enemies, danger, false);
            List<Point2D> safePoints = new ArrayList<>();
            for (Point2D p : trackPoints) {
                if (p.getDamageInterest() == 0) {
                    safePoints.add(p);
                }
            }
            if (!safePoints.isEmpty()) {
                trackPoints = safePoints;
            }
            weightDistanceToWayPoint(trackPoints, nextWP);
            trackPoints.sort(moveCostComparator);
            Point2D nextPoint = trackPoints.get(0);
            Enemy nextEnemy = getTarget(nextPoint, enemies, danger, true);
            double nextEnemyDistance;
            nextEnemyDistance = currentPosition.getDistanceTo(nextEnemy.getUnit());
            LivingUnit targetEnemy = currentEnemy.getUnit();
            if (!safePoints.isEmpty() && nextPoint.getDamageInterest() <= currentPosition.getDamageInterest()) {
//                    && !(targetEnemy instanceof Building)) {
                target = nextPoint;
            }
            if (currentEnemy.getInterest() < nextEnemy.getInterest() &&
                    nextEnemyDistance < self.getCastRange() + nextEnemy.getUnit().getRadius()) {
                targetEnemy = nextEnemy.getUnit();
            }

            goTo(target, targetEnemy, false);
        } else {
            weightDistanceToWayPoint(trackPoints, nextWP);
            trackPoints.sort(moveCostComparator);
            goToPoint(trackPoints, null, false);
        }
        previousLevel = self.getLevel();
        previousHealth = self.getLife();
    }

    private void goToPoint(List<Point2D> trackPoints, LivingUnit enemy, boolean forceFrosBolt) {
        if (trackPoints.size() > 0) {
            Point2D target = trackPoints.get(0);
            goTo(target, enemy, forceFrosBolt);
        } else {
            //TODO(tdurakov): HOW???
        }


    }


    public Enemy getTarget(Point2D target, List<LivingUnit> enemies, Danger danger, boolean checkPotential) {
        Enemy targetEnemy = null;
        List<Enemy> enemiesToAtack = new ArrayList<>();
        double currentRange = checkPotential ? self.getVisionRange() + 500 : self.getCastRange();
        for (LivingUnit lu : enemies) {
            double distanceToSelf = target.getDistanceTo(lu);
            if (distanceToSelf <= currentRange + lu.getRadius()) {
                Enemy e = new Enemy(game, lu, target, danger);
                enemiesToAtack.add(e);
            } else {
                break;
            }
        }
        enemiesToAtack.sort(attackInterestCostComparator);
        if (enemiesToAtack.size() > 0)
            targetEnemy = enemiesToAtack.get(0);
        return targetEnemy;
    }

    /**
     * Инциализируем стратегию.
     * <p/>
     * Для этих целей обычно можно использовать конструктор, однако в данном случае мы хотим инициализировать генератор
     * случайных чисел значением, полученным от симулятора игры.
     */
    private void initializeStrategy(Wizard self, Game game, World world) {
        if (random == null) {
            random = new Random(game.getRandomSeed());
            getEnemyBuildingsMarks(world);
            previousLevel = self.getLevel();
            previousHealth = self.getLife();
            tickWithDamage = -10;
            double mapSize = game.getMapSize();

            waypointsByLane.put(LaneType.MIDDLE, new Point2D[]{
                    new Point2D(100.0D, mapSize - 100.0D),
                    random.nextBoolean()
                            ? new Point2D(600.0D, mapSize - 200.0D)
                            : new Point2D(200.0D, mapSize - 600.0D),
                    new Point2D(800.0D, mapSize - 800.0D),
                    new Point2D(mapSize - 600.0D, 600.0D)
            });

            waypointsByLane.put(LaneType.TOP, new Point2D[]{
                    new Point2D(100.0D, mapSize - 100.0D),
                    new Point2D(100.0D, mapSize - 400.0D),
                    new Point2D(200.0D, mapSize - 800.0D),
                    new Point2D(200.0D, mapSize * 0.75D),
                    new Point2D(200.0D, mapSize * 0.5D),
                    new Point2D(200.0D, mapSize * 0.25D),
                    new Point2D(200.0D, 200.0D),
                    new Point2D(mapSize * 0.25D, 200.0D),
                    new Point2D(mapSize * 0.5D, 200.0D),
                    new Point2D(mapSize * 0.75D, 200.0D),
                    new Point2D(mapSize - 200.0D, 200.0D)
            });

            waypointsByLane.put(LaneType.BOTTOM, new Point2D[]{
                    new Point2D(100.0D, mapSize - 100.0D),
                    new Point2D(400.0D, mapSize - 100.0D),
                    new Point2D(800.0D, mapSize - 200.0D),
                    new Point2D(mapSize * 0.25D, mapSize - 200.0D),
                    new Point2D(mapSize * 0.5D, mapSize - 200.0D),
                    new Point2D(mapSize * 0.75D, mapSize - 200.0D),
                    new Point2D(mapSize - 200.0D, mapSize - 200.0D),
                    new Point2D(mapSize - 200.0D, mapSize * 0.75D),
                    new Point2D(mapSize - 200.0D, mapSize * 0.5D),
                    new Point2D(mapSize - 200.0D, mapSize * 0.25D),
                    new Point2D(mapSize - 200.0D, 200.0D)
            });

            switch ((int) self.getId()) {
                case 1:
                case 2:
                case 6:
                case 7:
                    lane = LaneType.TOP;
                    break;
                case 3:
                case 8:
                    lane = LaneType.MIDDLE;
                    break;
                case 4:
                case 5:
                case 9:
                case 10:
                    lane = LaneType.BOTTOM;
                    break;
                default:
            }

            waypoints = waypointsByLane.get(lane);

            // Наша стратегия исходит из предположения, что заданные нами ключевые точки упорядочены по убыванию
            // дальности до последней ключевой точки. Сейчас проверка этого факта отключена, однако вы можете
            // написать свою проверку, если решите изменить координаты ключевых точек.

            /*Point2D lastWaypoint = waypoints[waypoints.length - 1];

            Preconditions.checkState(ArrayUtils.isSorted(waypoints, (waypointA, waypointB) -> Double.compare(
                    waypointB.getDistanceTo(lastWaypoint), waypointA.getDistanceTo(lastWaypoint)
            )));*/
        }
    }

    /**
     * Сохраняем все входные данные в полях класса для упрощения доступа к ним.
     */
    private void initializeTick(Wizard self, World world, Game game, Move move) {
        this.self = self;
        this.world = world;
        this.game = game;
        this.move = move;
    }

    /**
     * Данный метод предполагает, что все ключевые точки на линии упорядочены по уменьшению дистанции до последней
     * ключевой точки. Перебирая их по порядку, находим первую попавшуюся точку, которая находится ближе к последней
     * точке на линии, чем волшебник. Это и будет следующей ключевой точкой.
     * <p/>
     * Дополнительно проверяем, не находится ли волшебник достаточно близко к какой-либо из ключевых точек. Если это
     * так, то мы сразу возвращаем следующую ключевую точку.
     */
    private Point2D getNextWaypoint() {
        int lastWaypointIndex = waypoints.length - 1;
        Point2D lastWaypoint = waypoints[lastWaypointIndex];

        for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex) {
            Point2D waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(self) <= WAYPOINT_RADIUS) {
                return waypoints[waypointIndex + 1];
            }

            if (lastWaypoint.getDistanceTo(waypoint) < lastWaypoint.getDistanceTo(self)) {
                return waypoint;
            }
        }

        return lastWaypoint;
    }

    /**
     * Действие данного метода абсолютно идентично действию метода {@code getNextWaypoint}, если перевернуть массив
     * {@code waypoints}.
     */
    private Point2D getPreviousWaypoint() {
        Point2D firstWaypoint = waypoints[0];

        for (int waypointIndex = waypoints.length - 1; waypointIndex > 0; --waypointIndex) {
            Point2D waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(self) <= WAYPOINT_RADIUS) {
                return waypoints[waypointIndex - 1];
            }

            if (firstWaypoint.getDistanceTo(waypoint) < firstWaypoint.getDistanceTo(self)) {
                return waypoint;
            }
        }

        return firstWaypoint;
    }

    /**
     * Простейший способ перемещения волшебника.
     */
    private void goTo(Point2D point, LivingUnit target, boolean forceFireBall) {
        double attackAngle;
        if (target != null) {
            attackAngle = self.getAngleTo(target);
            if (StrictMath.abs(attackAngle) < game.getStaffSector() / 2.0D) {
                if (fireBall
                        && self.getRemainingCooldownTicksByAction()[4] == 0
                        && self.getMana() >= game.getFireballManacost()
                        && (target instanceof Wizard || target instanceof Building || (forceFireBall
                        && target.getLife() > game.getMagicMissileDirectDamage()))) {
                    move.setAction(ActionType.FIREBALL);
                } else if (self.getRemainingCooldownTicksByAction()[2] == 0) {
                    move.setAction(ActionType.MAGIC_MISSILE);
                }
                move.setCastAngle(attackAngle);
                move.setMinCastDistance(point.getDistanceTo(target) - target.getRadius() + game.getMagicMissileRadius());
            }
        } else {
            attackAngle = self.getAngleTo(point.getX(), point.getY());
        }
        move.setTurn(attackAngle);
        if (point != null) {
            double moveAngle = self.getAngleTo(point.x, point.y);
            double[] speed = getSpeedForStrafe(moveAngle);
            move.setSpeed(speed[0]);
            move.setStrafeSpeed(speed[1]);
        }
    }

    /**
     * Находим ближайшую цель для атаки, независимо от её типа и других характеристик.
     */

    public static final class DistanceToUnitComparator implements Comparator<LivingUnit> {
        Wizard self;

        DistanceToUnitComparator(Wizard self) {
            this.self = self;
        }

        @Override
        public int compare(LivingUnit o1, LivingUnit o2) {
            double o1Distance = self.getDistanceTo(o1);
            double o2Distance = self.getDistanceTo(o2);
            if (o1Distance < o2Distance) {
                return -1;
            }
            if (o1Distance > o2Distance) {
                return 1;
            }
            return 0;
        }
    }

    public static final class AttackInterestCostComparator implements Comparator<Enemy> {

        @Override
        public int compare(Enemy e1, Enemy e2) {
            if (e1.getInterest() < e2.getInterest()) {
                return 1;
            }
            if (e1.getInterest() > e2.getInterest()) {
                return -1;
            }
            return 0;
        }
    }


    public static final class MoveCostComparator implements Comparator<Point2D> {

        @Override
        public int compare(Point2D o1, Point2D o2) {
            if (o1.getMoveInterest() < o2.getMoveInterest()) {
                return -1;
            }
            if (o1.getMoveInterest() > o2.getMoveInterest()) {
                return 1;
            }
            return 0;
        }
    }

    public static final class DamageCostComparator implements Comparator<Point2D> {

        @Override
        public int compare(Point2D o1, Point2D o2) {
            if (o1.getDamageInterest() < o2.getDamageInterest()) {
                return -1;
            }
            if (o1.getDamageInterest() > o2.getDamageInterest()) {
                return 1;
            }
            return 0;
        }
    }

    private Map<String, List<LivingUnit>> getNearest() {
        Map<String, List<LivingUnit>> nearestMap = new HashMap<>();
        DistanceToUnitComparator cmp = new DistanceToUnitComparator(self);
        //TREES
        List<LivingUnit> trees = new ArrayList<>(Arrays.asList(world.getTrees()));
        trees.sort(cmp);
        nearestMap.put(TREES, trees);

        //WIZARDS
        List<LivingUnit> friendlyWizards = new ArrayList<>();
        List<LivingUnit> enemyWizards = new ArrayList<>();
        for (Wizard w : world.getWizards()) {
            if (w.getId() == self.getId()) {
                continue;
            }
            if (self.getFaction() == w.getFaction()) {
                if (self.getDistanceTo(w) <= self.getVisionRange()) {
                    this.friendlyWizards++;
                }
                friendlyWizards.add(w);
            } else {
                if (self.getDistanceTo(w) <= self.getVisionRange()) {
                    this.enemyWizards++;
                }
                enemyWizards.add(w);
            }
        }
        friendlyWizards.sort(cmp);
        enemyWizards.sort(cmp);
        nearestMap.put(FRIENDLY_WIZARDS, friendlyWizards);
        nearestMap.put(ENEMY_WIZARDS, enemyWizards);

        //MINIONS
        List<LivingUnit> friendlyMinions = new ArrayList<>();
        List<LivingUnit> enemyMinions = new ArrayList<>();
        List<LivingUnit> neutralMinions = new ArrayList<>();
        for (Minion m : world.getMinions()) {
            if (self.getFaction() == m.getFaction()) {
                friendlyMinions.add(m);
            } else if (m.getFaction() == Faction.NEUTRAL) {
                if (m.getRemainingActionCooldownTicks() > 0 || m.getSpeedX() != 0 || m.getSpeedY() != 0) {
                    enemyMinions.add(m);
                } else {
                    neutralMinions.add(m);
                }
            } else {
                enemyMinions.add(m);
                if (self.getDistanceTo(m) <= self.getVisionRange() && m.getType() == MinionType.FETISH_BLOWDART) {
                    this.enemyFetish++;
                }

            }
        }
        friendlyMinions.sort(cmp);
        neutralMinions.sort(cmp);
        enemyMinions.sort(cmp);
        nearestMap.put(FRIENDLY_MINIONS, friendlyMinions);
        nearestMap.put(ENEMY_MINIONS, enemyMinions);
        nearestMap.put(NEUTRAL_UNITS, neutralMinions);

        //BUILDING
        List<LivingUnit> friendlyBuildings = new ArrayList<>();
        List<LivingUnit> enemyBuildings = new ArrayList<>();
        this.enemyBuildings = new HashMap<>();
        for (Building b : world.getBuildings()) {
            if (self.getFaction() == b.getFaction()) {
                friendlyBuildings.add(b);
            } else {
                enemyBuildings.add(b);
                this.enemyBuildings.put(new IntPoint((int) Math.round(b.getX()), (int) Math.round(b.getY())), b);
            }
        }
        friendlyBuildings.sort(cmp);
        enemyBuildings.sort(cmp);
        nearestMap.put(FRIENDLY_BUILDINGS, friendlyBuildings);
        nearestMap.put(ENEMY_BUILDINGS, enemyBuildings);
        List<LivingUnit> enemies = new ArrayList<>();
        //ENEMIES
        enemies.addAll(enemyWizards);
        enemies.addAll(enemyMinions);
        enemies.addAll(enemyBuildings);
        enemies.sort(cmp);
        nearestMap.put(ENEMIES, enemies);
        return nearestMap;
    }

    private final class BuildingMark {
        private BuildingType buildingType;
        private Point2D point;

        BuildingMark(BuildingType buildingType, Point2D point) {
            this.point = point;
            this.buildingType = buildingType;
        }

        public BuildingType getBuildingType() {
            return buildingType;
        }

        public void setBuildingType(BuildingType buildingType) {
            this.buildingType = buildingType;
        }

        public Point2D getPoint() {
            return point;
        }

        public void setPoint(Point2D point) {
            this.point = point;
        }
    }

    private final class Enemy {
        private LivingUnit unit;
        private double interest = 1.0;

        private Enemy(Game game, LivingUnit unit, Point2D target, Danger danger) {
            this.unit = unit;
            interest = game.getMagicMissileDirectDamage();
            interest *= (2 - unit.getLife() / unit.getMaxLife());
            boolean checkDamage = danger != Danger.LOW;
            if (unit instanceof Wizard) {
                interest *= 100;
                if (unit.getLife() <= game.getMagicMissileDirectDamage()) {
                    interest *= game.getWizardEliminationScoreFactor() == 0.0 ? 1.0 : 1 + game.getWizardEliminationScoreFactor();
                    interest *= 10.0;
                } else {
                    interest *= game.getWizardDamageScoreFactor() == 0.0 ? 1.0 : 1 + game.getWizardDamageScoreFactor();
                }
                if (checkDamage) {
                    interest += getWizardDamageForPoint(target, unit, danger);
                }
            } else if (unit instanceof Building) {
//                Building b = (Building) unit;
//                if (b.getType() == BuildingType.FACTION_BASE) {
//                    interest = game.getFactionBaseDamage();
//                } else {
//                    interest = game.getGuardianTowerDamage();
//                }
                interest *= 50;
                if (unit.getLife() <= game.getMagicMissileDirectDamage()) {
                    interest *= game.getBuildingEliminationScoreFactor() == 0.0 ? 1.0 : 1 + game.getBuildingEliminationScoreFactor();
                    interest *= 5.0;
                } else {
                    interest *= game.getBuildingDamageScoreFactor() == 0.0 ? 1.0 : 1 + game.getBuildingDamageScoreFactor();
                }
                if (checkDamage) {
                    interest += getBuildingDamageForPoint(target, unit, danger);
                }
            } else if (unit instanceof Minion) {
                Minion m = (Minion) unit;
                if (m.getType() == MinionType.FETISH_BLOWDART && enemyFetish > 1) {
                    interest *= 50;
                }

                if (unit.getLife() <= game.getMagicMissileDirectDamage()) {
                    interest *= game.getMinionEliminationScoreFactor() == 0.0 ? 1.0 : 1 + game.getMinionEliminationScoreFactor();
                    interest *= 10.0;
                } else {
                    interest *= game.getMinionDamageScoreFactor() == 0.0 ? 1.0 : 1 + game.getMinionDamageScoreFactor();
                }
                if (checkDamage) {
                    interest += getMinionDamageForPoint(target, unit, danger);
                }
            }

        }

        public double getInterest() {
            return interest;
        }

        public LivingUnit getUnit() {
            return unit;
        }
    }

    private final class IntPoint {
        private int x;
        private int y;

        public IntPoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            IntPoint intPoint = (IntPoint) o;

            if (x != intPoint.x) return false;
            return y == intPoint.y;

        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            return result;
        }

        public double getDistanceTo(double x, double y) {
            return StrictMath.hypot(this.x - x, this.y - y);
        }

        public double getDistanceTo(Point2D point) {
            return getDistanceTo(point.x, point.y);
        }

        public double getDistanceTo(Unit unit) {
            return getDistanceTo(unit.getX(), unit.getY());
        }
    }

    /**
     * Вспомогательный класс для хранения позиций на карте.
     */
    private static final class Point2D {
        private final double x;
        private final double y;
        private double moveInterest = 0.0;
        private double attackInterest = 0.0;
        private double damageInterest = 0.0;
        private List<Enemy> enemies = new ArrayList<>();

        private Point2D(Unit unit) {
            this.x = unit.getX();
            this.y = unit.getY();
        }

        private Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public List<Enemy> getEnemies() {
            return enemies;
        }

        public void appendToEnemies(Enemy e) {
            enemies.add(e);
        }

        public void appendToMoveInterest(double moveInterest) {
            this.moveInterest += moveInterest;
        }

        public void appendToAttackInterest(double attackInterest) {
            this.attackInterest += attackInterest;
        }

        public void appendToDamageInterest(double damageInterest) {
            this.damageInterest += damageInterest;
        }


        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getMoveInterest() {
            return moveInterest;
        }

        public double getAttackInterest() {
            return attackInterest;
        }

        public double getDamageInterest() {
            return damageInterest;
        }

        public boolean isValid() {
            return x >= 0 && x <= 4000d && y >= 0 && y <= 4000d;
        }

        public double getDistanceTo(double x, double y) {
            return StrictMath.hypot(this.x - x, this.y - y);
        }

        public double getDistanceTo(Point2D point) {
            return getDistanceTo(point.x, point.y);
        }

        public double getDistanceTo(Unit unit) {
            return getDistanceTo(unit.getX(), unit.getY());
        }

        public double getDistanceTo(BuildingMark unit) {
            return getDistanceTo(unit.getPoint());
        }
    }

}