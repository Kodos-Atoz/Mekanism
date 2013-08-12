package mekanism.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import mekanism.api.Object3D;
import mekanism.api.TransmitterNetworkRegistry;
import mekanism.common.PacketHandler.Transmission;
import mekanism.common.network.PacketDataRequest;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.google.common.io.ByteArrayDataInput;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class TileEntityMechanicalPipe extends TileEntity implements IMechanicalPipe, IFluidHandler, ITileNetwork
{
	/** The fake tank used for fluid transfer calculations. */
	public FluidTank dummyTank = new FluidTank(FluidContainerRegistry.BUCKET_VOLUME);
	
	/** The FluidStack displayed on this pipe. */
	public FluidStack refFluid = null;
	
	/** The fluid network currently in use by this pipe segment. */
	public FluidNetwork fluidNetwork;
	
	/** This pipe's active state. */
	public boolean isActive = false;
	
	/** The scale (0F -> 1F) of this pipe's fluid level. */
	public float fluidScale;
	
	@Override
	public void onTransfer(FluidStack fluidStack)
	{
		if(fluidStack.isFluidEqual(refFluid))
		{
			fluidScale = Math.min(1, fluidScale+((float)fluidStack.amount/50F));
		}
		else if(refFluid == null)
		{
			refFluid = fluidStack.copy();
			fluidScale += Math.min(1, ((float)fluidStack.amount/50F));
		}
	}
	
	@Override
	public FluidNetwork getNetwork()
	{
		if(fluidNetwork == null)
		{
			fluidNetwork = new FluidNetwork(this);
		}
		
		return fluidNetwork;
	}
	
	@Override
	public FluidNetwork getNetwork(boolean createIfNull)
	{
		if(fluidNetwork == null && createIfNull)
		{
			TileEntity[] adjacentPipes = PipeUtils.getConnectedPipes(this);
			HashSet<FluidNetwork> connectedNets = new HashSet<FluidNetwork>();
			
			for(TileEntity pipe : adjacentPipes)
			{
				if(pipe instanceof IMechanicalPipe && ((IMechanicalPipe)pipe).getNetwork(false) != null)
				{
					connectedNets.add(((IMechanicalPipe)pipe).getNetwork());
				}
			}
			
			if(connectedNets.size() == 0 || worldObj.isRemote)
			{
				fluidNetwork = new FluidNetwork(this);
			}
			else if(connectedNets.size() == 1)
			{
				fluidNetwork = connectedNets.iterator().next();
				fluidNetwork.pipes.add(this);
			}
			else {
				fluidNetwork = new FluidNetwork(connectedNets);
				fluidNetwork.pipes.add(this);
			}
		}
		
		return fluidNetwork;
	}

	@Override
	public void fixNetwork()
	{
		getNetwork().fixMessedUpNetwork(this);
	}
	
	@Override
	public void invalidate()
	{
		if(!worldObj.isRemote)
		{
			getNetwork().split(this);
		}
		
		super.invalidate();
	}
	
	@Override
	public void setNetwork(FluidNetwork network)
	{
		if(network != fluidNetwork)
		{
			removeFromNetwork();
			fluidNetwork = network;
		}
	}
	
	@Override
	public void removeFromNetwork()
	{
		if(fluidNetwork != null)
		{
			fluidNetwork.removePipe(this);
		}
	}

	@Override
	public void refreshNetwork() 
	{
		if(!worldObj.isRemote)
		{
			for(ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
			{
				TileEntity tileEntity = Object3D.get(this).getFromSide(side).getTileEntity(worldObj);
				
				if(tileEntity instanceof IMechanicalPipe)
				{
					getNetwork().merge(((IMechanicalPipe)tileEntity).getNetwork());
				}
			}
			
			getNetwork().refresh();
		}
	}
	
	@Override
	public void onChunkUnload() 
	{
		invalidate();
		TransmitterNetworkRegistry.getInstance().pruneEmptyNetworks();
	}
	
	@Override
	public void updateEntity()
	{
		if(worldObj.isRemote)
		{
			if(fluidScale > 0)
			{
				fluidScale -= .01;
			}
			else {
				refFluid = null;
			}
		}	
		else {		
			if(isActive)
			{
				IFluidHandler[] connectedAcceptors = PipeUtils.getConnectedAcceptors(this);
				
				for(IFluidHandler container : connectedAcceptors)
				{
					ForgeDirection side = ForgeDirection.getOrientation(Arrays.asList(connectedAcceptors).indexOf(container));
					
					if(container != null)
					{
						FluidStack received = container.drain(side, 100, false);
						
						if(received != null && received.amount != 0)
						{
							container.drain(side, getNetwork().emit(received, true, Object3D.get(this).getFromSide(side).getTileEntity(worldObj)), true);
						}
					}
				}
			}
		}
	}
	
	@Override
	public boolean canUpdate()
	{
		return true;
	}
	
	@Override
	public void validate()
	{
		super.validate();
		
		if(worldObj.isRemote)
		{
			PacketHandler.sendPacket(Transmission.SERVER, new PacketDataRequest().setParams(Object3D.get(this)));
		}
	}
	
	@Override
	public void handlePacketData(ByteArrayDataInput dataStream)
	{
		isActive = dataStream.readBoolean();
	}
	
	@Override
	public ArrayList getNetworkedData(ArrayList data)
	{
		data.add(isActive);
		return data;
	}
	
	@Override
    public void readFromNBT(NBTTagCompound nbtTags)
    {
        super.readFromNBT(nbtTags);

        isActive = nbtTags.getBoolean("isActive");
    }

	@Override
    public void writeToNBT(NBTTagCompound nbtTags)
    {
        super.writeToNBT(nbtTags);
        
        nbtTags.setBoolean("isActive", isActive);
    }
	
	@Override
	@SideOnly(Side.CLIENT)
	public AxisAlignedBB getRenderBoundingBox()
	{
		return INFINITE_EXTENT_AABB;
	}

	@Override
	public int fill(ForgeDirection from, FluidStack resource, boolean doFill)
	{
		if(!isActive)
		{
			return getNetwork().emit(resource, doFill, Object3D.get(this).getFromSide(from).getTileEntity(worldObj));
		}
		
		return 0;
	}

	@Override
	public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) 
	{
		return null;
	}

	@Override
	public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) 
	{
		return null;
	}

	@Override
	public boolean canFill(ForgeDirection from, Fluid fluid) 
	{
		return true;
	}

	@Override
	public boolean canDrain(ForgeDirection from, Fluid fluid) 
	{
		return true;
	}

	@Override
	public FluidTankInfo[] getTankInfo(ForgeDirection from) 
	{
		return new FluidTankInfo[] {dummyTank.getInfo()};
	}
}