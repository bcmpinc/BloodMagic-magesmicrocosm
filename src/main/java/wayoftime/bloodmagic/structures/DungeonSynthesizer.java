package wayoftime.bloodmagic.structures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.reflect.TypeToken;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import wayoftime.bloodmagic.BloodMagic;
import wayoftime.bloodmagic.common.block.BloodMagicBlocks;
import wayoftime.bloodmagic.common.tile.TileDungeonController;
import wayoftime.bloodmagic.common.tile.TileDungeonSeal;
import wayoftime.bloodmagic.gson.Serializers;
import wayoftime.bloodmagic.ritual.AreaDescriptor;
import wayoftime.bloodmagic.util.Constants;

public class DungeonSynthesizer
{
	public static boolean displayDetailedInformation = false;

	public Map<String, Map<Direction, List<BlockPos>>> availableDoorMasterMap = new HashMap<>(); // Map of doors. The
																									// Direction
																									// indicates what
																									// way this
																									// door faces.

	public List<AreaDescriptor> descriptorList = new ArrayList<>();

	private int activatedDoors = 0;
	private ResourceLocation specialRoomPool = BloodMagic.rl("room_pools/tier2/mine_entrances");

	private List<ResourceLocation> specialRoomBuffer = new ArrayList<>();
	private Map<ResourceLocation, Integer> placementsSinceLastSpecial = new HashMap<>();

	public void writeToNBT(CompoundTag tag)
	{
		String json = Serializers.GSON.toJson(availableDoorMasterMap);
		tag.putString(Constants.NBT.DOOR_MAP, json);

		ListTag listnbt = new ListTag();
		for (int i = 0; i < descriptorList.size(); ++i)
		{
			AreaDescriptor desc = descriptorList.get(i);
			CompoundTag compoundnbt = new CompoundTag();
			desc.writeToNBT(compoundnbt);
			listnbt.add(compoundnbt);

		}

		if (!listnbt.isEmpty())
		{
			tag.put(Constants.NBT.AREA_DESCRIPTORS, listnbt);
		}
	}

	public void readFromNBT(CompoundTag tag)
	{
		String testJson = tag.getString(Constants.NBT.DOOR_MAP);
		if (!testJson.isEmpty())
		{
			availableDoorMasterMap = Serializers.GSON.fromJson(testJson, new TypeToken<Map<String, Map<Direction, List<BlockPos>>>>()
			{
			}.getType());
		}

		ListTag listnbt = tag.getList(Constants.NBT.AREA_DESCRIPTORS, 10);

		for (int i = 0; i < listnbt.size(); ++i)
		{
			CompoundTag compoundnbt = listnbt.getCompound(i);
			AreaDescriptor.Rectangle rec = new AreaDescriptor.Rectangle(BlockPos.ZERO, 0);
			rec.readFromNBT(compoundnbt);
			descriptorList.add(rec);
		}
	}

	public BlockPos[] generateInitialRoom(ResourceLocation initialType, Random rand, ServerLevel world, BlockPos spawningPosition)
	{

//		String initialDoorName = "default";
		StructurePlaceSettings settings = new StructurePlaceSettings();
		Mirror mir = Mirror.NONE;

		settings.setMirror(mir);

		Rotation rot = Rotation.NONE;

		settings.setRotation(rot);
		settings.setIgnoreEntities(true);
//		settings.setChunkPos(null);
//		settings.addProcessor(new StoneToOreProcessor(0.0f));
		settings.setKnownShape(true);

//		ResourceLocation initialType = new ResourceLocation("bloodmagic:room_pools/test_pool_1");
		DungeonRoom initialRoom = DungeonRoomRegistry.getRandomDungeonRoom(initialType, rand);
		BlockPos roomPlacementPosition = initialRoom.getInitialSpawnOffsetForControllerPos(settings, spawningPosition);

//		System.out.println("Initial room offset: " + roomPlacementPosition);

//		DungeonRoom room = getRandomRoom(rand);
//		roomMap.put(pos, Pair.of(room, settings.copy()));
//		roomList.add(Pair.of(pos, Pair.of(room, settings.copy())));
		descriptorList.addAll(initialRoom.getAreaDescriptors(settings, roomPlacementPosition));

//		Map<Direction, List<BlockPos>> availableDoorMap = new HashMap<>();
//		availableDoorMasterMap.put(initialDoorName, availableDoorMap);

		for (Direction facing : Direction.values())
		{
			Map<String, List<BlockPos>> doorTypeMap = initialRoom.getAllDoorOffsetsForFacing(settings, facing, roomPlacementPosition);
//			System.out.println("doorTypeMap size: " + doorTypeMap.entrySet().size());
			for (Entry<String, List<BlockPos>> entry : doorTypeMap.entrySet())
			{
				if (!availableDoorMasterMap.containsKey(entry.getKey()))
				{
					availableDoorMasterMap.put(entry.getKey(), new HashMap<>());
				}

				Map<Direction, List<BlockPos>> doorDirectionMap = availableDoorMasterMap.get(entry.getKey());
				if (!doorDirectionMap.containsKey(facing))
				{
					doorDirectionMap.put(facing, new ArrayList<BlockPos>());
				}

				doorDirectionMap.get(facing).addAll(entry.getValue());
			}
		}

		initialRoom.placeStructureAtPosition(rand, settings, world, roomPlacementPosition);

//		System.out.println("Available door master map: " + availableDoorMasterMap);

		addNewControllerBlock(world, spawningPosition);

		// TODO: Generate door blocks based off of room's doors.
//		Map<Pair<Direction, BlockPos>, List<String>> doorTypeMap = room.getPotentialConnectedRoomTypes(settings, pos);
		List<DungeonDoor> doorTypeMap = initialRoom.getPotentialConnectedRoomTypes(settings, roomPlacementPosition);
//		System.out.println("Size of doorTypeMap: " + doorTypeMap.size());
		for (DungeonDoor dungeonDoor : doorTypeMap)
		{
			this.addNewDoorBlock(world, spawningPosition, dungeonDoor.doorPos, dungeonDoor.doorDir, dungeonDoor.doorType, 0, 0, dungeonDoor.getRoomList(), dungeonDoor.getSpecialRoomList());
		}

		BlockPos playerPos = initialRoom.getPlayerSpawnLocationForPlacement(settings, roomPlacementPosition);
		BlockPos portalLocation = initialRoom.getPortalOffsetLocationForPlacement(settings, roomPlacementPosition);

		return new BlockPos[] { playerPos, portalLocation };
	}

	public void addNewControllerBlock(ServerLevel world, BlockPos controllerPos)
	{
//		world.setBlockState(controllerPos, Blocks.LAPIS_BLOCK.getDefaultState(), 3);
		world.setBlock(controllerPos, BloodMagicBlocks.DUNGEON_CONTROLLER.get().defaultBlockState(), 3);
		BlockEntity tile = world.getBlockEntity(controllerPos);
		if (tile instanceof TileDungeonController)
		{
			((TileDungeonController) tile).setDungeonSynthesizer(this);
//			((TileDungeonSeal) tile).acceptDoorInformation(controllerPos, doorBlockPos, doorFacing, doorType, potentialRoomTypes);
		}
	}

	public void addNewDoorBlock(ServerLevel world, BlockPos controllerPos, BlockPos doorBlockPos, Direction doorFacing, String doorType, int newRoomDepth, int highestBranchRoomDepth, List<ResourceLocation> potentialRoomTypes, List<ResourceLocation> specialRoomTypes)
	{
		if (highestBranchRoomDepth < newRoomDepth)
		{
			highestBranchRoomDepth = newRoomDepth;
		}

		BlockPos doorBlockOffsetPos = doorBlockPos.relative(doorFacing).relative(Direction.UP, 2);
//		world.setBlockState(doorBlockOffsetPos, Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
		Direction rightDirection = doorFacing.getClockWise();
		for (int i = -1; i <= 1; i++)
		{
			for (int j = -1; j <= 1; j++)
			{
				if (i == 0 && j == 0)
				{
					continue;
				}

				world.setBlockAndUpdate(doorBlockOffsetPos.relative(rightDirection, i).relative(Direction.UP, j), BloodMagicBlocks.DUNGEON_BRICK_ASSORTED.get().defaultBlockState());
			}
		}

		ResourceLocation specialRoomType = getSpecialRoom(newRoomDepth, specialRoomTypes);
		if (specialRoomType != null)
		{
			DungeonRoom randomRoom = getRandomRoom(specialRoomType, world.random);
			if (randomRoom != null)
			{
				if (checkRequiredRoom(world, controllerPos, specialRoomType, doorBlockOffsetPos, randomRoom, world.random, doorBlockPos, doorFacing, doorType, newRoomDepth, highestBranchRoomDepth))
				{
					removeSpecialRoom(specialRoomType);
					world.setBlock(doorBlockOffsetPos.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
					return;
				}
			} else
			{
				if (displayDetailedInformation)
					System.out.println("Uh oh.");
			}
		}

		potentialRoomTypes = modifyRoomTypes(potentialRoomTypes);

		world.setBlock(doorBlockOffsetPos, BloodMagicBlocks.DUNGEON_SEAL.get().defaultBlockState(), 3);
		BlockEntity tile = world.getBlockEntity(doorBlockOffsetPos);
		if (tile instanceof TileDungeonSeal)
		{
			((TileDungeonSeal) tile).acceptDoorInformation(controllerPos, doorBlockPos, doorFacing, doorType, newRoomDepth, highestBranchRoomDepth, potentialRoomTypes);
		}
	}

	public List<ResourceLocation> modifyRoomTypes(List<ResourceLocation> potentialRoomTypes)
	{
		List<ResourceLocation> modifiedRoomTypes = new ArrayList<>(potentialRoomTypes);

		return modifiedRoomTypes;
	}

	public ResourceLocation getSpecialRoom(int currentRoomDepth, List<ResourceLocation> potentialSpecialRoomTypes)
	{
		if (potentialSpecialRoomTypes.isEmpty() || specialRoomBuffer.isEmpty())
		{
			return null;
		}

		for (ResourceLocation resource : potentialSpecialRoomTypes)
		{
			if (specialRoomBuffer.contains(resource))
			{
//				specialRoomBuffer.remove(resource);
				return resource;
			}
		}

		return potentialSpecialRoomTypes.get(0);
	}

	public void removeSpecialRoom(ResourceLocation resource)
	{
		if (specialRoomBuffer.contains(resource))
		{
			specialRoomBuffer.remove(resource);
		}
	}

	// TODO: Check the door that is going to be placed here. If the door can be
	// successfully added to the structure, add the area descriptors to the
	// synthesizer and then place a seal that contains the info to reconstruct the
	// room.

	public boolean checkRequiredRoom(ServerLevel world, BlockPos controllerPos, ResourceLocation specialRoomType, BlockPos doorBlockOffsetPos, DungeonRoom room, Random rand, BlockPos activatedDoorPos, Direction doorFacing, String activatedDoorType, int newRoomDepth, int highestBranchRoomDepth)
	{
		StructurePlaceSettings settings = new StructurePlaceSettings();
		Mirror mir = Mirror.NONE;

		settings.setMirror(mir);

		Rotation rot = Rotation.NONE;

		settings.setRotation(rot);
		settings.setIgnoreEntities(false);
//		settings.setChunkPos(null);
//		settings.addProcessor(new StoneToOreProcessor(0.0f));
		settings.setKnownShape(true);

		DungeonRoom placedRoom = null;
		Pair<Direction, BlockPos> activatedDoor = Pair.of(doorFacing, activatedDoorPos);
		Pair<Direction, BlockPos> addedDoor = null;
		BlockPos roomLocation = null;

		Direction oppositeDoorFacing = doorFacing.getOpposite();

		List<Rotation> rotationList = Rotation.getShuffled(rand);
		Rotation finalRotation = null;

		// Got a random room, now test if any of the rotations have a valid door.
		rotationCheck: for (Rotation initialRotation : rotationList)
		{
			settings.setRotation(initialRotation);

			// TODO: Change this to use a "requestedDoorType".
			List<BlockPos> otherDoorList = room.getDoorOffsetsForFacing(settings, activatedDoorType, oppositeDoorFacing, BlockPos.ZERO);
			if (otherDoorList != null && !otherDoorList.isEmpty())
			{
				// Going to connect to this door! ...Hopefully.
				int doorIndex = rand.nextInt(otherDoorList.size());
				BlockPos testDoor = otherDoorList.get(doorIndex);

				roomLocation = activatedDoorPos.subtract(testDoor).offset(doorFacing.getNormal());

				List<AreaDescriptor> descriptors = room.getAreaDescriptors(settings, roomLocation);
				for (AreaDescriptor testDesc : descriptors)
				{
					for (AreaDescriptor currentDesc : descriptorList)
					{
						if (testDesc.intersects(currentDesc))
						{
							// TODO: Better exit condition?
							break rotationCheck;
						}
					}
				}

//				roomMap.put(roomLocation, Pair.of(testingRoom, settings.copy()));
				descriptorList.addAll(descriptors);
				addedDoor = Pair.of(oppositeDoorFacing, testDoor.offset(roomLocation));

				placedRoom = room;
				finalRotation = initialRotation;

				break;
			}
		}

		if (placedRoom == null)
		{
			// Did not manage to place the room.
			return false;
		}

//		placedRoom.placeStructureAtPosition(rand, settings, world, roomLocation);
		spawnDoorBlock(world, controllerPos, specialRoomType, doorBlockOffsetPos, doorFacing, activatedDoorPos, activatedDoorType, newRoomDepth, highestBranchRoomDepth, room, finalRotation, roomLocation);

		//

		return true;
	}

	// May not need doorType
	public void spawnDoorBlock(ServerLevel world, BlockPos controllerPos, ResourceLocation specialRoomType, BlockPos doorBlockOffsetPos, Direction doorFacing, BlockPos activatedDoorPos, String activatedDoorType, int roomDepth, int highestBranchRoomDepth, DungeonRoom room, Rotation rotation, BlockPos roomLocation)
	{
		// TODO: Change to a Door Block that contains this info.
		// Make sure to store the `specialRoomType` for the key to check against; the
		// '#' character is removed.
		forcePlacementOfRoom(world, controllerPos, doorFacing, activatedDoorPos, activatedDoorType, roomDepth, highestBranchRoomDepth, room, rotation, roomLocation);
	}

	/**
	 * Returns how successful the placement of the room was.
	 * 
	 * @param world
	 * @param controllerPos
	 * @param roomType
	 * @param rand
	 * @param activatedDoorPos
	 * @param doorFacing
	 * @param activatedDoorType
	 * @param potentialRooms
	 * @param activatedRoomDepth     The depth that the Door was assigned.
	 * @param highestBranchRoomDepth The maximum depth for this path.
	 * @return
	 */
	public int addNewRoomToExistingDungeon(ServerLevel world, BlockPos controllerPos, ResourceLocation roomType, Random rand, BlockPos activatedDoorPos, Direction doorFacing, String activatedDoorType, List<ResourceLocation> potentialRooms, int activatedRoomDepth, int highestBranchRoomDepth)
	{
		System.out.println("Current room's depth info: " + activatedRoomDepth + "/" + highestBranchRoomDepth);
		for (int i = 0; i < 10; i++)
		{
			boolean testPlacement = attemptPlacementOfRandomRoom(world, controllerPos, roomType, rand, activatedDoorPos, doorFacing, activatedDoorType, activatedRoomDepth, highestBranchRoomDepth, potentialRooms, false);
			if (testPlacement)
			{
				return 0;
			}
		}

		ResourceLocation pathPool = new ResourceLocation("bloodmagic:room_pools/connective_corridors");
		if (attemptPlacementOfRandomRoom(world, controllerPos, pathPool, rand, activatedDoorPos, doorFacing, activatedDoorType, activatedRoomDepth, highestBranchRoomDepth, potentialRooms, true))
		{
			return 1;
		}

		return 2;
	}

	public boolean forcePlacementOfRoom(ServerLevel world, BlockPos controllerPos, Direction doorFacing, BlockPos activatedDoorPos, String activatedDoorType, int previousRoomDepth, int previousMaxDepth, DungeonRoom room, Rotation rotation, BlockPos roomLocation)
	{
		if (displayDetailedInformation)
			System.out.println("Forcing room! Room is: " + room);
		if (room == null)
		{
			return false;
		}

		StructurePlaceSettings settings = new StructurePlaceSettings();
		Mirror mir = Mirror.NONE;

		settings.setMirror(mir);

		Rotation rot = Rotation.NONE;

		settings.setRotation(rot);
		settings.setIgnoreEntities(false);
//		settings.setChunkPos(null);
//		settings.addProcessor(new StoneToOreProcessor(0.0f));
		settings.setKnownShape(true);
		settings.setRotation(rotation);

		DungeonRoom placedRoom = room;
		Pair<Direction, BlockPos> activatedDoor = Pair.of(doorFacing, activatedDoorPos);
		Pair<Direction, BlockPos> addedDoor = null;

//		System.out.println("Forced placed room name: " + roomName);

		Direction oppositeDoorFacing = doorFacing.getOpposite();
		addedDoor = Pair.of(oppositeDoorFacing, activatedDoorPos.relative(doorFacing));

		// Need to save: rotation, addedDoor, roomLocation, doorFacing, roomName

		settings.clearProcessors();
		settings.addProcessor(new StoneToOreProcessor(room.oreDensity));

		placedRoom.placeStructureAtPosition(world.random, settings, world, roomLocation);
		for (String doorType : placedRoom.doorMap.keySet())
		{
			if (!availableDoorMasterMap.containsKey(doorType))
			{
				availableDoorMasterMap.put(doorType, new HashMap<>());
			}

			Map<Direction, List<BlockPos>> availableDoorMap = availableDoorMasterMap.get(doorType);
			for (Direction facing : Direction.values())
			{
				if (!availableDoorMap.containsKey(facing))
				{
					availableDoorMap.put(facing, new ArrayList<>());
				}

				List<BlockPos> doorList = availableDoorMap.get(facing);
				doorList.addAll(placedRoom.getDoorOffsetsForFacing(settings, doorType, facing, roomLocation));
			}

			if (doorType.equals(activatedDoorType))
			{
				Direction activatedDoorFace = activatedDoor.getKey();
				if (availableDoorMap.containsKey(activatedDoorFace))
				{
					availableDoorMap.get(activatedDoorFace).remove(activatedDoor.getRight());
				}

				Direction addedDoorFace = addedDoor.getKey();
				if (availableDoorMap.containsKey(addedDoorFace))
				{
					availableDoorMap.get(addedDoorFace).remove(addedDoor.getRight());
				}
			}
		}

		List<DungeonDoor> doorTypeMap = placedRoom.getPotentialConnectedRoomTypes(settings, roomLocation);
		Collections.shuffle(doorTypeMap);
		boolean addedHigherPath = false;

		for (DungeonDoor dungeonDoor : doorTypeMap)
		{
			if (addedDoor.getKey().equals(dungeonDoor.doorDir) && addedDoor.getRight().equals(dungeonDoor.doorPos))
			{
				continue;
			}

			int newRoomDepth = previousRoomDepth + (addedHigherPath ? world.random.nextInt(2) * 2 - 1 : 1);
			addedHigherPath = true;
			{
				if (displayDetailedInformation)
					System.out.println("Room list: " + dungeonDoor.getRoomList());
				this.addNewDoorBlock(world, controllerPos, dungeonDoor.doorPos, dungeonDoor.doorDir, dungeonDoor.doorType, newRoomDepth, previousMaxDepth, dungeonDoor.getRoomList(), dungeonDoor.getSpecialRoomList());
			}
		}

		return true;
	}

	public boolean attemptPlacementOfRandomRoom(ServerLevel world, BlockPos controllerPos, ResourceLocation roomType, Random rand, BlockPos activatedDoorPos, Direction doorFacing, String activatedDoorType, int previousRoomDepth, int previousMaxDepth, List<ResourceLocation> potentialRooms, boolean extendCorriDoors)
	{
		StructurePlaceSettings settings = new StructurePlaceSettings();
		Mirror mir = Mirror.NONE;

		settings.setMirror(mir);

		Rotation rot = Rotation.NONE;

		settings.setRotation(rot);
		settings.setIgnoreEntities(false);
//		settings.setChunkPos(null);
//		settings.addProcessor(new StoneToOreProcessor(0.0f));
		settings.setKnownShape(true);

		DungeonRoom placedRoom = null;
		Pair<Direction, BlockPos> activatedDoor = Pair.of(doorFacing, activatedDoorPos);
		Pair<Direction, BlockPos> addedDoor = null;
		BlockPos roomLocation = null;

		Direction oppositeDoorFacing = doorFacing.getOpposite();
		DungeonRoom testingRoom = getRandomRoom(roomType, rand);

		if (displayDetailedInformation)
			System.out.println("Room type: " + roomType);

		List<Rotation> rotationList = Rotation.getShuffled(rand);

		// Got a random room, now test if any of the rotations have a valid door.
		rotationCheck: for (Rotation initialRotation : rotationList)
		{
			settings.setRotation(initialRotation);

			// TODO: Change this to use a "requestedDoorType".
			List<BlockPos> otherDoorList = testingRoom.getDoorOffsetsForFacing(settings, activatedDoorType, oppositeDoorFacing, BlockPos.ZERO);
			if (otherDoorList != null && !otherDoorList.isEmpty())
			{
				// Going to connect to this door! ...Hopefully.
				int doorIndex = rand.nextInt(otherDoorList.size());
				BlockPos testDoor = otherDoorList.get(doorIndex);

				roomLocation = activatedDoorPos.subtract(testDoor).offset(doorFacing.getNormal());

				List<AreaDescriptor> descriptors = testingRoom.getAreaDescriptors(settings, roomLocation);
				for (AreaDescriptor testDesc : descriptors)
				{
					for (AreaDescriptor currentDesc : descriptorList)
					{
						if (testDesc.intersects(currentDesc))
						{
							// TODO: Better exit condition?
							break rotationCheck;
						}
					}
				}

				settings.clearProcessors();
				settings.addProcessor(new StoneToOreProcessor(testingRoom.oreDensity));

//				roomMap.put(roomLocation, Pair.of(testingRoom, settings.copy()));
				descriptorList.addAll(descriptors);
				addedDoor = Pair.of(oppositeDoorFacing, testDoor.offset(roomLocation));

				placedRoom = testingRoom;

				break;
			}
		}

		if (placedRoom == null)
		{
			// Did not manage to place the room.
			return false;
		}

		placedRoom.placeStructureAtPosition(rand, settings, world, roomLocation);

		activatedDoors++;
		checkSpecialRoomRequirements(previousRoomDepth);

		for (String doorType : placedRoom.doorMap.keySet())
		{
			if (!availableDoorMasterMap.containsKey(doorType))
			{
				availableDoorMasterMap.put(doorType, new HashMap<>());
			}

			Map<Direction, List<BlockPos>> availableDoorMap = availableDoorMasterMap.get(doorType);
			for (Direction facing : Direction.values())
			{
				if (!availableDoorMap.containsKey(facing))
				{
					availableDoorMap.put(facing, new ArrayList<>());
				}

				List<BlockPos> doorList = availableDoorMap.get(facing);
				doorList.addAll(placedRoom.getDoorOffsetsForFacing(settings, doorType, facing, roomLocation));
			}

			if (doorType.equals(activatedDoorType))
			{
				Direction activatedDoorFace = activatedDoor.getKey();
				if (availableDoorMap.containsKey(activatedDoorFace))
				{
					availableDoorMap.get(activatedDoorFace).remove(activatedDoor.getRight());
				}

				Direction addedDoorFace = addedDoor.getKey();
				if (availableDoorMap.containsKey(addedDoorFace))
				{
					availableDoorMap.get(addedDoorFace).remove(addedDoor.getRight());
				}
			}
		}

		List<DungeonDoor> doorTypeMap = placedRoom.getPotentialConnectedRoomTypes(settings, roomLocation);

		Collections.shuffle(doorTypeMap);
		boolean addedHigherPath = false;

		for (DungeonDoor dungeonDoor : doorTypeMap)
		{
			if (addedDoor.getKey().equals(dungeonDoor.doorDir) && addedDoor.getRight().equals(dungeonDoor.doorPos))
			{
				continue;
			}

			if (extendCorriDoors)
			{
				this.addNewDoorBlock(world, controllerPos, dungeonDoor.doorPos, dungeonDoor.doorDir, activatedDoorType, previousRoomDepth, previousMaxDepth, potentialRooms, new ArrayList<>());
			} else
			{
				int newRoomDepth = previousRoomDepth + (addedHigherPath ? world.random.nextInt(2) * 2 - 1 : 1);
				addedHigherPath = true;

				if (displayDetailedInformation)
					System.out.println("Room list: " + dungeonDoor.getRoomList());
				this.addNewDoorBlock(world, controllerPos, dungeonDoor.doorPos, dungeonDoor.doorDir, dungeonDoor.doorType, newRoomDepth, previousMaxDepth, dungeonDoor.getRoomList(), dungeonDoor.getSpecialRoomList());
			}
		}

		return true;
	}

	public void checkSpecialRoomRequirements(int currentRoomDepth)
	{
		for (ResourceLocation res : this.placementsSinceLastSpecial.keySet())
		{
			placementsSinceLastSpecial.put(res, placementsSinceLastSpecial.get(res) + 1);
		}

		if (displayDetailedInformation)
			System.out.println("Number of activated doors: " + activatedDoors);
		if (activatedDoors == 3)
		{
//			specialRoomBuffer.add(specialRoomPool);
		}
	}

	public static DungeonRoom getRandomRoom(ResourceLocation roomType, Random rand)
	{
//		System.out.println("Dungeon size: " + DungeonRoomRegistry.dungeonWeightMap.size());
		return DungeonRoomRegistry.getRandomDungeonRoom(roomType, rand);
	}

	public static DungeonRoom getDungeonRoom(ResourceLocation dungeonName)
	{
		return DungeonRoomRegistry.getDungeonRoom(dungeonName);
	}
}